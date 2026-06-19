package com.example.dart.notify;

import com.example.dart.dart.DartClient;
import com.example.dart.dart.DocumentNotReadyException;
import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.parse.DocumentParser;
import com.example.dart.quote.StockQuoteClient;
import com.example.dart.service.DocumentService;
import com.example.dart.util.KoreanMoney;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 호재 공시 알림을 2단계로 조립한다.
 *
 * 공시는 속도가 생명이라 감지 즉시 헤더만 먼저 보내고(composeHeader, 네트워크 없음),
 * 본문 조회·규모 계산처럼 시간 걸리는 분석은 후속 메시지로 분리한다(composeFollowup).
 * 후속에서 계약금액을 매출·시총 대비 비율로 환산해 "이게 큰 건인지"를 바로 보여준다.
 */
public class AlertComposer {

    private static final Logger log = LoggerFactory.getLogger(AlertComposer.class);

    /** DART 목록 API는 접수 "일"만 주므로, 시각은 감지 시점(폴링 7초 주기 ≈ 게시 시각)으로 보여준다. */
    private static final DateTimeFormatter DETECT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DocumentService documentService;
    private final NewsFilter newsFilter;
    private final StockQuoteClient quoteClient;
    private final DartClient dartClient;

    public AlertComposer(DocumentService documentService, NewsFilter newsFilter,
                         StockQuoteClient quoteClient, DartClient dartClient) {
        this.documentService = documentService;
        this.newsFilter = newsFilter;
        this.quoteClient = quoteClient;
        this.dartClient = dartClient;
    }

    /** 1단계 — 감지 즉시 전송할 헤더. 본문 조회 없음(가장 빠름). */
    public String composeHeader(Disclosure d, NewsFilter.TitleMatch match) {
        boolean correction = NewsFilter.isCorrection(d.reportNm());
        return String.format(
                "%s **%s%s · %s** | %s — %s\n접수 %s · 감지 %s · 제출인 %s\n%s\n_규모 분석 보강 중…_",
                correction ? "🔁" : "📋", correction ? "[정정] " : "",
                match.category(), d.marketName(), d.corpName(), d.reportNm(),
                formatDate(d.rceptDt()), DETECT_TIME_FMT.format(ZonedDateTime.now(KST)),
                d.flrNm(), dartUrl(d.rceptNo()));
    }

    /**
     * 2단계 — 본문 조회 후 규모를 담은 후속 메시지. "시총·매출 대비" 알림임을 타이틀로 구분한다:
     *   📊 시총·매출 대비 | 회사 — 공시명 / 💰 계약금액·매출 대비·시총 대비 / 📈 매출액·계약상대방·계약기간.
     * 링크는 넣지 않는다(원문은 헤더 알림의 링크로 확인). 비동기 호출이라 실패해도 헤더는 이미 나갔으므로 안전.
     */
    public String composeFollowup(Disclosure d) {
        String rceptNo = d.rceptNo();
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출 대비** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));
        try {
            DocumentParser.ContractInfo c = documentService.contractInfo(rceptNo);

            // 매출액: 본문 최근매출액 우선, 없으면 DART 재무 API(원문 lag와 무관)로 보강.
            OptionalLong revenue = c.recentRevenueWon().isPresent()
                    ? c.recentRevenueWon()
                    : dartClient.recentRevenueWon(d.corpCode());

            if (c.contractWon().isPresent()) {
                long won = c.contractWon().getAsLong();
                log.info("규모 분석 [{} - {}] 계약금액 {}원 ({})",
                        d.corpName(), d.reportNm(), won, KoreanMoney.format(won));
                sb.append("\n💰 계약금액 ").append(KoreanMoney.format(won));

                // 매출 대비 % — 공시 명시값(거래소 표준) 우선, 없으면 계약금액÷매출액. 연환산은 안 함(공시값과 일치).
                String salesLabel = DocumentParser.salesRatioLabel(won, revenue, c.salesRatioPct());
                if (salesLabel != null) {
                    log.info("규모 분석 {}", salesLabel);
                    sb.append(" · ").append(salesLabel);
                }

                // 시총 대비 % — 종목코드로 조회한 시가총액 대비(딜 규모 vs 회사 가치라 총액 기준).
                if (cap.isPresent() && cap.getAsLong() > 0) {
                    double r = won * 100.0 / cap.getAsLong();
                    log.info("시총 대비 {}% (시총 {}원)", String.format("%.1f", r), cap.getAsLong());
                    sb.append(String.format(" · 시총 대비 %.1f%%", r));
                }
            } else {
                log.info("규모 분석 [{} - {}] 계약금액 미추출", d.corpName(), d.reportNm());
            }

            // 📈 핵심정보 한 줄 — 시총·매출 원시값 + 계약 부가정보(있는 것만).
            List<String> info = new ArrayList<>();
            cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
            revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
            if (c.counterparty() != null) info.add("계약상대방 " + c.counterparty());
            if (c.period() != null) info.add("계약기간 " + c.period());
            if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));
        } catch (DocumentNotReadyException e) {
            // 원문이 아직 공개되지 않음(014) — 영구 실패가 아니므로 삼키지 않고 전파해
            // PollerService가 잠시 뒤 재조회하게 한다.
            throw e;
        } catch (Exception e) {
            log.warn("규모 분석 실패 — 헤더 알림은 이미 전송됨: {} - {}", d.corpName(), d.reportNm(), e);
            sb.append("\n(상세 내역 조회 실패)");
        }
        return sb.toString();
    }

    /**
     * 계약이 아닌 호재(자기주식취득·배당·소각 등)용 — 대비 비율은 의미가 없으므로 회사 규모(시총·매출)만 보여준다.
     * 원문(document.xml)을 거치지 않고 종목코드→시총, corp_code→매출(재무 API)만 쓰므로 lag·재시도가 없다.
     */
    public String composeScaleOnly(Disclosure d) {
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        OptionalLong revenue = dartClient.recentRevenueWon(d.corpCode());
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));
        List<String> info = new ArrayList<>();
        cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
        revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));
        return sb.toString();
    }

    private static String dartUrl(String rceptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;
    }

    /** "20260610" → "2026-06-10" */
    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
