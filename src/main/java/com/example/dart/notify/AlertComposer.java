package com.example.dart.notify;

import com.example.dart.dart.DartClient;
import com.example.dart.dart.DocumentNotReadyException;
import com.example.dart.filter.NewsFilter;
import com.example.dart.kind.KindClient;
import com.example.dart.kind.KindDocumentClient;
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
    /** 빠른 경로용 — KIND 뷰어 본문 소스. KIND 비활성 시 null이며, 그땐 빠른 경로가 즉시 폴백된다. */
    private final KindClient kindClient;
    private final KindDocumentClient kindDocClient;
    private final DocumentParser documentParser;

    public AlertComposer(DocumentService documentService, NewsFilter newsFilter,
                         StockQuoteClient quoteClient, DartClient dartClient,
                         KindClient kindClient, KindDocumentClient kindDocClient,
                         DocumentParser documentParser) {
        this.documentService = documentService;
        this.newsFilter = newsFilter;
        this.quoteClient = quoteClient;
        this.dartClient = dartClient;
        this.kindClient = kindClient;
        this.kindDocClient = kindDocClient;
        this.documentParser = documentParser;
    }

    /** 1단계 — 감지 즉시 전송할 헤더. 본문 조회 없음(가장 빠름). */
    public String composeHeader(Disclosure d, NewsFilter.TitleMatch match) {
        boolean correction = NewsFilter.isCorrection(d.reportNm());
        return String.format(
                "%s **%s%s · %s** | %s — %s\n접수 %s · 감지 %s · 제출인 %s\n%s",
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
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        try {
            DocumentParser.ContractInfo c = documentService.contractInfo(d.rceptNo());

            // 매출액: 본문 최근매출액 우선, 없으면 DART 재무 API(원문 lag와 무관)로 보강.
            OptionalLong revenue = c.recentRevenueWon().isPresent()
                    ? c.recentRevenueWon()
                    : dartClient.recentRevenueWon(d.corpCode());

            return buildFollowup(d, c, cap, revenue);
        } catch (DocumentNotReadyException e) {
            // 원문이 아직 공개되지 않음(014) — 영구 실패가 아니므로 삼키지 않고 전파해
            // PollerService가 잠시 뒤 재조회하게 한다.
            throw e;
        } catch (Exception e) {
            log.warn("규모 분석 실패 — 헤더 알림은 이미 전송됨: {} - {}", d.corpName(), d.reportNm(), e);
            return String.format("📊 **%s시총·매출 대비** | %s — %s\n(상세 내역 조회 실패)",
                    NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm());
        }
    }

    /**
     * 2단계(빠른 경로) — DART 원문(document.xml, status 014로 수 분~수 시간 지연) 대신 KIND 뷰어 본문에서
     * 규모를 즉시 뽑아 {@link #composeFollowup}과 동일 포맷의 후속을 만든다. DART가 먼저 감지한 계약도
     * 헤더 직후 수초 내 %를 받게 하는 게 목적. DART rcept_no는 KIND acptNo와 다르므로 회사+제목으로 매칭한다.
     * KIND 본문을 못 구하면(미게시·미설정·조회 실패) 예외를 던져 호출자가 DART 원문 경로로 폴백하게 한다.
     */
    public String composeFollowupFast(Disclosure d) throws Exception {
        if (kindClient == null || kindDocClient == null) {
            throw new IllegalStateException("KIND 소스 미설정 — 빠른 경로 불가");
        }
        String acptNo = kindClient.findAcptNo(d.rceptDt(), d.corpName(), d.reportNm())
                .orElseThrow(() -> new IllegalStateException(
                        "KIND 오늘 목록에 일치 공시 없음: " + d.corpName() + " - " + d.reportNm()));

        KindDocumentClient.KindDocument doc = kindDocClient.fetch(acptNo);
        DocumentParser.ContractInfo c =
                documentParser.extractContractFromText(documentParser.htmlToPlainText(doc.bodyHtml()));

        // 시총: KIND 뷰어가 읽은 종목코드 우선, 없으면 공시 자체의 종목코드.
        String stockCode = doc.stockCode() != null ? doc.stockCode() : d.stockCode();
        OptionalLong cap = (stockCode != null && !stockCode.isBlank())
                ? quoteClient.marketCapWon(stockCode)
                : OptionalLong.empty();
        // 매출액: 본문 최근매출액 우선, 없으면 DART 재무 API.
        OptionalLong revenue = c.recentRevenueWon().isPresent()
                ? c.recentRevenueWon()
                : dartClient.recentRevenueWon(d.corpCode());

        log.info("빠른 규모 분석(KIND acptNo={}) [{} - {}] 계약금액 {}", acptNo, d.corpName(), d.reportNm(),
                c.contractWon().isPresent() ? KoreanMoney.format(c.contractWon().getAsLong()) : "미추출");
        return buildFollowup(d, c, cap, revenue);
    }

    /**
     * 후속 메시지 본문 조립 — DART 원문·KIND 본문 두 경로가 공유한다.
     *   📊 시총·매출 대비 | 회사 — 공시명 / 💰 계약금액·매출 대비·시총 대비 / 📈 시총·매출액·계약상대방·계약기간.
     */
    private String buildFollowup(Disclosure d, DocumentParser.ContractInfo c,
                                 OptionalLong cap, OptionalLong revenue) {
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출 대비** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));

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
        // 자기주식취득결정이면 취득금액을 KIND 뷰어 본문에서 즉시 뽑아 한 줄 덧붙인다(직접취득결정만).
        if (NewsFilter.isTreasuryAcquisition(d.reportNm())) {
            treasuryAcquisitionAmount(d).ifPresent(won -> {
                log.info("자기주식 취득금액 [{} - {}] {}원 ({})",
                        d.corpName(), d.reportNm(), won, KoreanMoney.format(won));
                sb.append("\n💰 취득금액 ").append(KoreanMoney.format(won));
            });
        }
        List<String> info = new ArrayList<>();
        cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
        revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));
        return sb.toString();
    }

    /**
     * 자기주식취득결정 본문에서 취득금액을 KIND 뷰어 본문으로 즉시 조회한다(DART 원문 014 지연 회피).
     * KIND 미설정·미게시·파싱 실패면 empty — 금액 줄만 빠지고 시총·매출 알림은 정상 발송된다.
     */
    private OptionalLong treasuryAcquisitionAmount(Disclosure d) {
        if (kindClient == null || kindDocClient == null) return OptionalLong.empty();
        try {
            String acptNo = kindClient.findAcptNo(d.rceptDt(), d.corpName(), d.reportNm()).orElse(null);
            if (acptNo == null) return OptionalLong.empty();
            KindDocumentClient.KindDocument doc = kindDocClient.fetch(acptNo);
            return documentParser.acquisitionAmountWon(documentParser.htmlToPlainText(doc.bodyHtml()));
        } catch (Exception e) {
            log.info("자기주식 취득금액 조회 실패(금액 줄 생략): {} - {} ({})",
                    d.corpName(), d.reportNm(), e.toString());
            return OptionalLong.empty();
        }
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
