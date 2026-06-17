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
        return String.format(
                "📋 **%s · %s** | %s — %s\n접수 %s · 감지 %s · 제출인 %s\n%s\n_규모 분석 보강 중…_",
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
        StringBuilder sb = new StringBuilder(
                String.format("📊 **시총·매출 대비** | %s — %s", d.corpName(), d.reportNm()));
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

                // 매출 대비 % — 공시 명시값 우선, 없으면 계약금액÷매출액.
                Double pct = c.salesRatioPct();
                if (pct == null && revenue.isPresent() && revenue.getAsLong() > 0) {
                    pct = won * 100.0 / revenue.getAsLong();
                }
                if (pct != null) {
                    log.info("매출 대비 {}%", String.format("%.1f", pct));
                    sb.append(String.format(" · 매출 대비 %.1f%%", pct));
                }

                // 시총 대비 % — 종목코드로 시가총액 조회.
                OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
                if (cap.isPresent() && cap.getAsLong() > 0) {
                    double r = won * 100.0 / cap.getAsLong();
                    log.info("시총 대비 {}% (시총 {}원)", String.format("%.1f", r), cap.getAsLong());
                    sb.append(String.format(" · 시총 대비 %.1f%%", r));
                }
            } else {
                log.info("규모 분석 [{} - {}] 계약금액 미추출", d.corpName(), d.reportNm());
            }

            // 📈 핵심정보 한 줄 — 있는 항목만 깔끔하게.
            List<String> info = new ArrayList<>();
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

    private static String dartUrl(String rceptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;
    }

    /** "20260610" → "2026-06-10" */
    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
