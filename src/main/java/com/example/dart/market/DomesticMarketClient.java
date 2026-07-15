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

    /**
     * 네이버 실시간 지수 투자자 트렌드 API — 코스피/코스닥 개인·외국인·기관 순매수(억원).
     * KIS 오픈API(FHPTJ04030000)는 시장코드 0001이 전부 0이라 코스피만 못 주고 전체(999)로 폴백돼 값이 틀렸다.
     * 네이버는 코스피/코스닥을 정확히 분리해 실시간으로 준다. 예: {"personalValue":"+26,018","foreignValue":"-16,042",...}
     */
    private static final String INVESTOR_TREND_API = "https://m.stock.naver.com/api/index/%s/trend";
    private static final List<Symbol> INVESTOR_MARKETS = List.of(
            new Symbol("KOSPI", "코스피"),
            new Symbol("KOSDAQ", "코스닥"));
    /** 네이버 투자자 순매수 값 단위: 억원 → 원 환산 계수. */
    private static final long EOK_TO_WON = 100_000_000L;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DomesticMarketClient() {
        this.httpClient = TrustStores.newHttpClient();
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

    /**
     * 코스피·코스닥 '시장 전체' 투자자 순매수(개인·외국인·기관)를 네이버 실시간 지수 트렌드에서 가져온다.
     * KIS 오픈API가 코스피만 분리해 주지 못해(0001=0) 값이 계속 틀렸던 것을 대체한다. 값은 억원이라 원으로 환산한다.
     * 실패한 시장은 건너뛰고, 전부 실패면 빈 목록을 돌려 헤드라인이 발송되지 않게 한다(앱을 멈추지 않는다).
     */
    public List<InvestorNet> investorFlows() {
        List<InvestorNet> flows = new ArrayList<>();
        for (Symbol m : INVESTOR_MARKETS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(String.format(INVESTOR_TREND_API, m.code())))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "Mozilla/5.0")   // 네이버도 UA 없으면 거부
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("네이버 시장 수급 조회 실패 ({}): status={}", m.label(), response.statusCode());
                    continue;
                }
                parseInvestorNet(response.body(), m.label()).ifPresent(flows::add);
            } catch (Exception e) {
                log.warn("네이버 시장 수급 조회 중 오류 ({}): {}", m.label(), e.toString());
            }
        }
        return flows;
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

    /**
     * 네이버 지수 투자자 트렌드 응답에서 개인/외국인/기관 순매수를 읽어 억원→원으로 환산한다.
     * 예: {"personalValue":"+26,018","foreignValue":"-16,042","institutionalValue":"-10,933"} (부호·콤마 포함).
     * 세 값이 전부 0/빈값(장 시작 전 등)이면 empty. (네트워크 분리 — 테스트용 정적 메서드)
     */
    static Optional<InvestorNet> parseInvestorNet(String json, String marketLabel) {
        try {
            JsonNode n = new ObjectMapper().readTree(json);
            long frgn = parseEokWon(n.path("foreignValue").asText(""));
            long orgn = parseEokWon(n.path("institutionalValue").asText(""));
            long prsn = parseEokWon(n.path("personalValue").asText(""));
            if (frgn == 0 && orgn == 0 && prsn == 0) return Optional.empty();
            return Optional.of(new InvestorNet(marketLabel, frgn, orgn, prsn));
        } catch (Exception e) {
            log.warn("네이버 시장 수급({}) JSON 파싱 실패: {}", marketLabel, e.toString());
            return Optional.empty();
        }
    }

    /** "+26,018"·"-16,042" 등 부호·콤마 포함 억원 문자열을 원(long)으로. 빈값·"-"·파싱실패는 0. (테스트용) */
    static long parseEokWon(String s) {
        String cleaned = s.replace(",", "").replace("+", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-")) return 0L;
        try {
            return Long.parseLong(cleaned) * EOK_TO_WON;
        } catch (NumberFormatException e) {
            return 0L;
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

    /** 시장 라벨 + 개인/외국인/기관 순매수(원). 네이버 지수 투자자 트렌드에서 파싱 — kis.MarketInvestorFlow로 매핑된다. */
    public record InvestorNet(String market, long foreignWon, long institutionWon, long individualWon) {}

    /** 현재가(레벨) + 전일 대비 등락률(%). */
    record Snapshot(double price, double pct) {}
}
