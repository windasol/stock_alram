package com.example.dart.market;

import com.example.dart.util.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 야후 파이낸스 비공식 차트 API로 국내 지수(코스피·코스닥)와 원달러 환율을 조회한다.
 * 시황 분석이 "코스피가 왜 빠지나"를 지수에 앵커링하고, 외국인 매도의 핵심 원인인 환율까지 엮어
 * 설명할 수 있도록 LLM에 넘길 '실측 재료'를 보탠다. (미국 선물을 다루는 {@link UsFuturesClient}와 동일 패턴)
 *
 * KIS·네이버에는 국내 지수/환율 조회가 없어 야후로 가져온다. 키 불필요(비공식·무료).
 * 회사망 SSL 검사 프록시 때문에 새 HttpClient엔 TrustStores.systemDefault()를 적용한다(다른 클라이언트와 동일).
 * 일부 실패는 건너뛰고, 전부 실패면 null을 반환해 리포트는 나머지 데이터로 계속된다(앱을 멈추지 않는다).
 */
public class DomesticMarketClient {

    private static final Logger log = LoggerFactory.getLogger(DomesticMarketClient.class);
    private static final String API = "https://query1.finance.yahoo.com/v8/finance/chart/%s";

    /** 국내 지수 — 야후 심볼과 표시 라벨. (코스피·코스닥 종합지수) */
    private static final List<Symbol> INDICES = List.of(
            new Symbol("^KS11", "코스피"),
            new Symbol("^KQ11", "코스닥"));
    /** 원달러 환율 야후 심볼 (USD/KRW). 레벨(예: 1,350원)이 절대값으로 의미가 커 따로 다룬다. */
    private static final String FX_SYMBOL = "KRW=X";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DomesticMarketClient() {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 국내 지수 한 줄(예: "🇰🇷 **국내 지수** | 코스피 -0.8%, 코스닥 +0.5%").
     * 일부 실패는 건너뛰고, 전부 실패(미조회·차단 등)면 null — 리포트가 이 블록을 생략한다.
     */
    public String indexSummaryLine() {
        List<Quote> quotes = new ArrayList<>();
        for (Symbol s : INDICES) {
            Optional<Snapshot> snap = fetch(s.code());
            if (snap.isPresent()) quotes.add(new Quote(s.label(), snap.get().pct()));
        }
        return formatIndexSummary(quotes);
    }

    /**
     * 수급 알림 헤드라인용 국내 지수 한 줄 — 지수 값(레벨)과 전일 대비 방향·등락률까지.
     * 예: "🇰🇷 코스피 2,750.32 ▲ +0.82% · 코스닥 850.10 ▼ -0.35%".
     * {@link #indexSummaryLine()}(등락률만, 시황 리포트용)과 달리 현재 값(레벨)을 보존한다. 전부 실패면 null.
     */
    public String indexHeadlineLine() {
        List<IndexQuote> quotes = new ArrayList<>();
        for (Symbol s : INDICES) {
            fetch(s.code()).ifPresent(snap -> quotes.add(new IndexQuote(s.label(), snap.price(), snap.pct())));
        }
        return formatIndexHeadline(quotes);
    }

    /**
     * 원달러 환율 한 줄(예: "💱 **원달러** | 1,350.2원 (+0.9%)"). 실패면 null.
     * 환율은 등락률뿐 아니라 레벨까지 표기한다 — "1,350원 돌파" 같은 절대값이 외국인 매매에 직접 작용하기 때문.
     */
    public String fxSummaryLine() {
        return fetch(FX_SYMBOL).map(DomesticMarketClient::formatFx).orElse(null);
    }

    private Optional<Snapshot> fetch(String symbol) {
        try {
            String url = String.format(API, URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0")   // 야후는 UA 없으면 거부(429/401)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("국내 지수·환율 조회 실패 (symbol={}): status={}", symbol, response.statusCode());
                return Optional.empty();
            }
            return parseSnapshot(response.body());
        } catch (Exception e) {
            log.warn("국내 지수·환율 조회 중 오류 (symbol={}): {}", symbol, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 야후 차트 응답에서 현재가와 전일 대비 등락률(%)을 뽑는다.
     * meta.regularMarketPrice 와 meta.previousClose(없으면 chartPreviousClose)로 (현재/전일-1)*100.
     * 값이 없거나 전일가 0·파싱 실패면 empty.
     */
    Optional<Snapshot> parseSnapshot(String json) {
        try {
            JsonNode meta = mapper.readTree(json).path("chart").path("result").path(0).path("meta");
            double price = meta.path("regularMarketPrice").asDouble(Double.NaN);
            double prev = meta.path("previousClose").asDouble(
                    meta.path("chartPreviousClose").asDouble(Double.NaN));
            if (Double.isNaN(price) || Double.isNaN(prev) || prev == 0.0) return Optional.empty();
            return Optional.of(new Snapshot(price, (price - prev) / prev * 100.0));
        } catch (Exception e) {
            log.warn("국내 지수·환율 JSON 파싱 실패: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 지수 등락률 목록을 "🇰🇷 **국내 지수** | 코스피 -0.8%, ..." 한 줄로. 빈 목록이면 null. (순수 함수 — 테스트용) */
    static String formatIndexSummary(List<Quote> quotes) {
        if (quotes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("🇰🇷 **국내 지수** | ");
        for (int i = 0; i < quotes.size(); i++) {
            if (i > 0) sb.append(", ");
            Quote q = quotes.get(i);
            sb.append(String.format("%s %+.1f%%", q.label(), q.pct()));
        }
        return sb.toString();
    }

    /**
     * 지수 값·방향·등락률 목록을 "🇰🇷 코스피 2,750.32 ▲ +0.82% · 코스닥 850.10 ▼ -0.35%" 한 줄로.
     * 화살표는 상승 ▲ / 하락 ▼ / 보합 —. 빈 목록이면 null. (순수 함수 — 테스트용)
     */
    static String formatIndexHeadline(List<IndexQuote> quotes) {
        if (quotes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("🇰🇷 ");
        for (int i = 0; i < quotes.size(); i++) {
            if (i > 0) sb.append(" · ");
            IndexQuote q = quotes.get(i);
            String arrow = q.pct() > 0 ? "▲" : q.pct() < 0 ? "▼" : "—";
            sb.append(String.format("%s %,.2f %s %+.2f%%", q.label(), q.price(), arrow, q.pct()));
        }
        return sb.toString();
    }

    /** 환율 스냅샷을 "💱 **원달러** | 1,350.2원 (+0.9%)" 한 줄로. (순수 함수 — 테스트용) */
    static String formatFx(Snapshot snap) {
        return String.format("💱 **원달러** | %,.1f원 (%+.1f%%)", snap.price(), snap.pct());
    }

    /** 야후 심볼 ↔ 표시 라벨. */
    private record Symbol(String code, String label) {}

    /** 표시 라벨 + 등락률(%). */
    record Quote(String label, double pct) {}

    /** 표시 라벨 + 현재 값(레벨) + 전일 대비 등락률(%). (수급 헤드라인용) */
    record IndexQuote(String label, double price, double pct) {}

    /** 현재가(레벨) + 전일 대비 등락률(%). */
    record Snapshot(double price, double pct) {}
}
