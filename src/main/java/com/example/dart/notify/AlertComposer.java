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
import com.example.dart.trade.TradeSignalListener;
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
    /** 자동매매 트리거 리스너 — 비활성 시 null(무동작). 계약 규모 확정 시점에 호출한다. */
    private TradeSignalListener tradeListener;

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

    /** 자동매매 트리거 리스너를 등록한다(선택). null이면 자동매매 미동작. */
    public void setTradeSignalListener(TradeSignalListener listener) {
        this.tradeListener = listener;
    }

    /** 계약 규모 확정 시 자동매매 리스너에 신호 전달 — 리스너 없음·정정·종목코드 없음·비율 미상이면 무시. */
    private void fireTradeSignal(Disclosure d, long contractWon, OptionalLong revenue, Double statedPct) {
        if (tradeListener == null) return;
        String code = d.stockCode();
        if (code == null || code.isBlank() || NewsFilter.isCorrection(d.reportNm())) return;
        java.util.OptionalDouble ratio = DocumentParser.salesRatioValue(contractWon, revenue, statedPct);
        if (ratio.isEmpty()) return;
        try {
            tradeListener.onContractSignal(d.rceptNo(), d.corpName(), code, contractWon, revenue, ratio.getAsDouble());
        } catch (Exception e) {
            log.warn("자동매매 신호 전달 실패: {} - {}", d.corpName(), d.reportNm(), e);
        }
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

            // 자동매매 트리거 — 계약 규모(매출 대비 비율)가 확정된 이 지점에서 신호를 넘긴다. 임계·중복·한도는 리스너가 판단.
            fireTradeSignal(d, won, revenue, c.salesRatioPct());

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
     * 계약이 아닌 호재(배당·소각 등)용 — 대비 비율은 의미가 없으므로 회사 규모(시총·매출)만 보여준다.
     * 원문(document.xml)을 거치지 않고 종목코드→시총, corp_code→매출(재무 API)만 쓰므로 lag·재시도가 없다.
     * 자기주식취득결정은 취득금액·시총대비%까지 붙이는 {@link #composeTreasury}로 따로 처리한다.
     */
    public String composeScaleOnly(Disclosure d) {
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        OptionalLong revenue = dartClient.recentRevenueWon(d.corpCode());
        return buildScale(d, cap, revenue, OptionalLong.empty(), "취득금액");
    }

    /**
     * 자기주식취득결정용 — 시총·매출에 더해 취득금액과 시총 대비 매입 강도(%)를 붙인다.
     * 취득금액은 KIND 뷰어 본문 우선(즉시), 못 구하면 DART 원문에서 파싱해 폴백한다. DART 원문이 아직
     * 미공개(014)면 {@link DocumentNotReadyException}을 전파해 호출자가 잠시 뒤 재조회하게 한다 —
     * 단발 조회로 금액이 누락되던 문제를 막는다. 두 소스 모두 실패하면 금액 줄 없이 시총·매출만 반환한다.
     */
    public String composeTreasury(Disclosure d) {
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        OptionalLong revenue = dartClient.recentRevenueWon(d.corpCode());
        OptionalLong amount = amountFromKind(d, documentParser::acquisitionAmountWon);
        if (amount.isEmpty()) {
            // 원문 014면 DocumentNotReadyException 전파(재시도 트리거)
            amount = amountFromDart(d, documentParser::acquisitionAmountWon);
        }
        return buildScale(d, cap, revenue, amount, "취득금액");
    }

    /**
     * 자기주식 신탁계약 "체결" 결정용 — 시총·매출에 더해 신탁계약금액과 시총 대비 매입 강도(%)를 붙인다.
     * 신탁 서식의 금액 라벨은 "취득예정금액"이 아니라 "계약금액(원)"이라, 계약(수주공급)과 같은 파서
     * ({@link DocumentParser#extractContractFromText})로 금액을 뽑는다. 매출 대비는 신탁계약엔 의미가 없어
     * 넣지 않는다. 조회·폴백·재시도(014 전파)는 {@link #composeTreasury}와 동일 구조로 공유한다.
     */
    public String composeTreasuryTrust(Disclosure d) {
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        OptionalLong revenue = dartClient.recentRevenueWon(d.corpCode());
        java.util.function.Function<String, OptionalLong> parse =
                t -> documentParser.extractContractFromText(t).contractWon();
        OptionalLong amount = amountFromKind(d, parse);
        if (amount.isEmpty()) {
            amount = amountFromDart(d, parse);   // 원문 014면 DocumentNotReadyException 전파(재시도 트리거)
        }
        return buildScale(d, cap, revenue, amount, "신탁계약금액");
    }

    /**
     * 주식소각결정용 — 시총·매출에 더해 소각예정금액과 시총 대비 매입 강도(%)를 붙인다.
     * 금액 라벨은 "소각예정금액(원)"이라 전용 파서({@link DocumentParser#cancellationAmountWon})를 쓴다.
     * 조회·폴백·재시도(014 전파)는 {@link #composeTreasury}와 동일 구조로 공유한다.
     */
    public String composeCancellation(Disclosure d) {
        OptionalLong cap = quoteClient.marketCapWon(d.stockCode());
        OptionalLong revenue = dartClient.recentRevenueWon(d.corpCode());
        OptionalLong amount = amountFromKind(d, documentParser::cancellationAmountWon);
        if (amount.isEmpty()) {
            amount = amountFromDart(d, documentParser::cancellationAmountWon);   // 원문 014면 재시도 트리거
        }
        return buildScale(d, cap, revenue, amount, "소각예정금액");
    }

    /**
     * 시총·매출 메시지 본문 조립 — composeScaleOnly·composeTreasury·composeTreasuryTrust·composeCancellation이 공유한다.
     * amount가 있으면(자기주식 취득금액/신탁계약금액) amountLabel과 금액, 시총 대비 매입 강도(%)를 함께 표시한다.
     */
    private String buildScale(Disclosure d, OptionalLong cap, OptionalLong revenue,
                              OptionalLong amount, String amountLabel) {
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));
        if (amount.isPresent()) {
            long won = amount.getAsLong();
            log.info("자기주식 {} [{} - {}] {}원 ({})",
                    amountLabel, d.corpName(), d.reportNm(), won, KoreanMoney.format(won));
            sb.append("\n💰 ").append(amountLabel).append(" ").append(KoreanMoney.format(won));
            if (cap.isPresent() && cap.getAsLong() > 0) {
                double r = won * 100.0 / cap.getAsLong();
                log.info("자기주식 시총 대비 {}% (시총 {}원)", String.format("%.1f", r), cap.getAsLong());
                sb.append(String.format(" · 시총 대비 %.1f%%", r));
            }
        }
        List<String> info = new ArrayList<>();
        cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
        revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));
        return sb.toString();
    }

    /**
     * 자기주식 공시 본문에서 금액을 KIND 뷰어 본문으로 즉시 조회한다(DART 원문 014 지연 회피).
     * 금액 파서는 인자로 받는다 — 직접취득결정은 취득예정금액, 신탁계약 체결은 계약금액.
     * KIND 미설정·미게시·파싱 실패면 empty — 호출자가 DART 원문으로 폴백한다.
     */
    private OptionalLong amountFromKind(Disclosure d, java.util.function.Function<String, OptionalLong> parse) {
        if (kindClient == null || kindDocClient == null) return OptionalLong.empty();
        try {
            String acptNo = kindClient.findAcptNo(d.rceptDt(), d.corpName(), d.reportNm()).orElse(null);
            if (acptNo == null) return OptionalLong.empty();
            KindDocumentClient.KindDocument doc = kindDocClient.fetch(acptNo);
            return parse.apply(documentParser.htmlToPlainText(doc.bodyHtml()));
        } catch (Exception e) {
            log.info("KIND 금액 조회 실패 — DART 원문으로 폴백: {} - {} ({})",
                    d.corpName(), d.reportNm(), e.toString());
            return OptionalLong.empty();
        }
    }

    /**
     * 자기주식 공시 금액을 DART 원문(document.xml)에서 파싱한다 — KIND 폴백용. 금액 파서는 인자로 받는다.
     * 원문 미공개(014)면 {@link DocumentNotReadyException}을 그대로 전파해 호출자가 재조회하게 한다.
     * 그 외 조회·파싱 실패는 삼키고 empty — 금액 줄만 빠지고 시총·매출 알림은 정상 발송된다.
     */
    private OptionalLong amountFromDart(Disclosure d, java.util.function.Function<String, OptionalLong> parse) {
        try {
            String text = documentService.toPlainText(d.rceptNo());
            return parse.apply(text);
        } catch (DocumentNotReadyException e) {
            throw e;
        } catch (Exception e) {
            log.info("DART 원문 금액 조회 실패(금액 줄 생략): {} - {} ({})",
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
