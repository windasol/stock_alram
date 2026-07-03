package com.example.dart.kis;

import com.example.dart.util.TrustStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 한국투자증권(KIS) Open API 클라이언트 — 급등 정찰에 쓰는 "등락률순위" 조회만 담당한다.
 *
 * 인증: appkey/appsecret(시스템 환경변수에서 주입)로 OAuth 토큰을 발급받아 Bearer로 호출한다.
 * 토큰은 유효기간 1일이고 잦은 재발급은 한도(분당 1회)·알림톡 발송을 유발하므로, 파일에 영속화해
 * 재시작 후에도 살아있는 토큰을 재사용한다.
 *
 * 도메인: 실전(openapi:9443) / 모의(openapivts:29443). 모의투자 앱키는 실전 도메인에서 시세조회(inquire-price)가
 * EGW02004("실전 도메인은 모의 앱키로 호출 불가")로 거부되므로, 키 종류에 맞는 도메인을 써야 한다(paper 플래그).
 * 시세·순위·업종 조회는 두 도메인 모두 실제 시장 데이터를 주며 계좌번호가 필요 없다.
 */
public class KisClient {

    private static final Logger log = LoggerFactory.getLogger(KisClient.class);

    private static final String REAL_BASE_URL  = "https://openapi.koreainvestment.com:9443";
    private static final String PAPER_BASE_URL = "https://openapivts.koreainvestment.com:29443";
    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final String FLUCTUATION_RANK_PATH = "/uapi/domestic-stock/v1/ranking/fluctuation";
    private static final String TR_FLUCTUATION_RANK = "FHPST01700000";
    private static final String VOLUME_RANK_PATH = "/uapi/domestic-stock/v1/quotations/volume-rank";
    private static final String TR_VOLUME_RANK = "FHPST01710000";
    private static final String INQUIRE_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String TR_INQUIRE_PRICE = "FHKST01010100";
    private static final String INQUIRE_ASKING_PRICE_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-asking-price";
    private static final String TR_INQUIRE_ASKING_PRICE = "FHKST01010200";
    private static final String INQUIRE_TIME_CHART_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String TR_TIME_CHART = "FHKST03010200";   // 주식당일분봉조회
    private static final String FOREIGN_INSTITUTION_TOTAL_PATH =
            "/uapi/domestic-stock/v1/quotations/foreign-institution-total";
    private static final String TR_FOREIGN_INSTITUTION_TOTAL = "FHPTJ04400000";   // 국내기관_외국인 매매종목가집계
    private static final String INQUIRE_INVESTOR_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-investor";
    private static final String TR_INQUIRE_INVESTOR = "FHKST01010900";   // 종목별 투자자 매매동향(마감 후 확정)
    private static final String FRGNMEM_TRADE_ESTIMATE_PATH =
            "/uapi/domestic-stock/v1/quotations/frgnmem-trade-estimate";
    private static final String TR_FRGNMEM_TRADE_ESTIMATE = "FHKST644100C0";   // 외국계 매매종목 가집계(HTS [0430], 실시간 추정)
    private static final String INVESTOR_TIME_BY_MARKET_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market";
    private static final String TR_MARKET_INVESTOR_TIME = "FHPTJ04030000";   // 시장별 투자자매매동향(시세) — 시장 전체 외국인·기관 순매수(가집계, 시간별)
    private static final String HOLIDAY_PATH = "/uapi/domestic-stock/v1/quotations/chk-holiday";
    private static final String TR_HOLIDAY = "CTCA0903R";   // 국내휴장일조회 — 기준일 이후 개장 여부(opnd_yn)
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /**
     * 가집계 순매수 거래대금(frgn/orgn_ntby_tr_pbmn) 단위 — 원이 아니라 백만원이라 원으로 환산해 저장한다.
     * (실측: 응답값 6자리 ≈ 360,000 → 백만원 환산 시 3,600억으로 현실적. 원이면 36만원으로 비현실적.)
     * 단위가 다르게 확인되면 이 상수만 바꾸면 된다(예: 천원이면 1_000L).
     */
    static final long NTBY_PBMN_UNIT_WON = 1_000_000L;

    /**
     * 외국인·기관 수급 랭킹 조회 대상 투자자 구분 — 외국인/기관계.
     * etcCode는 FID_ETC_CLS_CODE(1: 외국인, 2: 기관계), amountField는 응답에서 읽을 순매수 거래대금 필드명,
     * label은 알림 표시용 라벨.
     */
    public enum Investor {
        FOREIGN("1", "frgn_ntby_tr_pbmn", "🌍 외국인"),
        INSTITUTION("2", "orgn_ntby_tr_pbmn", "🏛 기관");

        final String etcCode;
        final String amountField;
        final String label;

        Investor(String etcCode, String amountField, String label) {
            this.etcCode = etcCode;
            this.amountField = amountField;
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 토큰 만료 이만큼 전에 미리 재발급한다. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(10);

    /**
     * 외국인·기관 가집계 호출 사이 최소 간격(ms) — 유량제한(EGW00201 초당 거래건수 초과) 회피.
     * 한 주기에 매수/매도×(외국인·기관·동시) = 6회를 연달아 쏘면, 특히 모의 도메인(≈초당 1~2건)에선
     * 뒤쪽 호출(보통 매도)이 EGW00201로 빈 응답이 와 매도 칸이 비는 증상이 난다. 호출을 간격을 둬 분산한다.
     */
    private static final long FI_CALL_MIN_INTERVAL_MS = 1_100L;
    /** 직전 가집계 호출 시각(ms). 모든 호출이 단일 폴러 스레드에서 순차 실행되므로 동기화 불필요. */
    private long lastFiCallAt = 0L;

    private final String appKey;
    private final String appSecret;
    /** 등락률순위 조회 시장구분 — J: KRX, NX: NXT, UN: 통합(KRX+NXT). NXT 거래시간(연장세션)까지 잡으려면 UN. */
    private final String marketDivCode;
    /** API 도메인 — 모의투자 앱키면 모의 도메인, 실전 앱키면 실전 도메인. */
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path tokenFile;

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public KisClient(String appKey, String appSecret, String marketDivCode, boolean paper, Path tokenFile) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.marketDivCode = (marketDivCode == null || marketDivCode.isBlank()) ? "J" : marketDivCode;
        this.baseUrl = paper ? PAPER_BASE_URL : REAL_BASE_URL;
        this.tokenFile = tokenFile;
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        loadTokenFromFile();
    }

    /** 현재 조회 시장구분(J/NX/UN) — 폴러가 운영시간·로그를 맞추는 데 쓴다. */
    public String marketDivCode() {
        return marketDivCode;
    }

    /** 기본 시장구분(생성자 주입값)으로 등락률순위를 조회한다. */
    public List<VolumeRankItem> topGainers() {
        return topGainers(marketDivCode);
    }

    /**
     * 등락률순위 — 전일 종가 대비 등락률(prdy_ctrt) 상위 종목을 내림차순으로 받아온다(최대 30건).
     * 시장구분(J: KRX, NX: NXT, UN: 통합)에 따라 조회한다. 거래량·거래대금·주가 하한은 두지 않고
     * "오늘 많이 오른 종목"만 본다 — 임계 필터는 폴러가 등락률로만 적용한다.
     * 시간대별(정규장/NXT 애프터마켓)로 시장구분을 바꿔 호출할 수 있게 인자로 받는다.
     *
     * @return 급등 판단에 쓸 값만 담은 목록. 실패 시 빈 목록.
     */
    public List<VolumeRankItem> topGainers(String marketDivCode) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=" + marketDivCode  // J: KRX, NX: NXT, UN: 통합(KRX+NXT)
                    + "&FID_COND_SCR_DIV_CODE=20170"
                    + "&FID_INPUT_ISCD=0000"                  // 전체 종목
                    + "&FID_RANK_SORT_CLS_CODE=0"             // 0: 상승률순
                    + "&FID_INPUT_CNT_1=0"                    // 누적 일수(0: 당일)
                    + "&FID_PRC_CLS_CODE=1"                   // 1: 전일 종가 대비(prdy_ctrt 기준 정렬)
                    + "&FID_DIV_CLS_CODE=0"                   // 0: 전체
                    + "&FID_TRGT_CLS_CODE=0000000000"
                    + "&FID_TRGT_EXLS_CLS_CODE=0000000000"
                    + "&FID_INPUT_PRICE_1="                   // 주가 하한 없음(순수 등락률)
                    + "&FID_INPUT_PRICE_2="
                    + "&FID_VOL_CNT="                         // 거래량 하한 없음
                    + "&FID_RSFL_RATE1="                      // 등락률 하한은 폴러에서 적용
                    + "&FID_RSFL_RATE2=";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + FLUCTUATION_RANK_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_FLUCTUATION_RANK)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseFluctuationRank(response.body());
        } catch (Exception e) {
            log.warn("KIS 등락률순위 조회 실패: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 거래대금순위 — 당일 누적 거래대금(acml_tr_pbmn) 상위 종목을 내림차순으로 받아온다(최대 30건).
     * "지금 어느 섹터가 활발한가"를 거래대금으로 보려는 용도라 등락률 조건 없이 거래대금이d사항  큰 종목만 본다.
     * 시장구분(J: KRX, NX: NXT, UN: 통합)에 따라 조회 — 시간대별로 폴러가 맞춰 호출한다.
     *
     * @return 거래대금 상위 종목 목록. 실패 시 빈 목록.
     */
    public List<TradingValueItem> topByTradingValue(String marketDivCode) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=" + marketDivCode  // J: KRX, NX: NXT, UN: 통합
                    + "&FID_COND_SCR_DIV_CODE=20171"
                    + "&FID_INPUT_ISCD=0000"                  // 전체 종목
                    + "&FID_DIV_CLS_CODE=0"                   // 0: 전체(보통주+우선주)
                    + "&FID_BLNG_CLS_CODE=3"                  // 3: 거래금액(거래대금)순
                    + "&FID_TRGT_CLS_CODE=111111111"
                    + "&FID_TRGT_EXLS_CLS_CODE=0000000000"
                    + "&FID_INPUT_PRICE_1=1000"               // 동전주 제외(1,000원 이상)
                    + "&FID_INPUT_PRICE_2="
                    + "&FID_VOL_CNT="
                    + "&FID_INPUT_DATE_1=";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + VOLUME_RANK_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_VOLUME_RANK)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseVolumeRank(response.body());
        } catch (Exception e) {
            log.warn("KIS 거래대금순위 조회 실패: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 외국인·기관 매매종목가집계(가집계) — 투자자(외국인/기관)별 순매수/순매도 상위 종목을 받아온다(최대 ~30건).
     * 코스피+코스닥 전체(FID_INPUT_ISCD=0000)를 순매수 거래대금(금액정렬) 기준으로 정렬한다.
     * 이 엔드포인트는 세션 시장구분(J/NX)이 아니라 고정값 V/16449를 쓰며, 가집계 특성상 정규장 중에만 의미가 있다.
     *
     * @param inv     투자자 구분(외국인/기관계)
     * @param buySide true면 순매수상위(가장 많이 산), false면 순매도상위(가장 많이 판)
     * @return 순매수 거래대금 순 종목 목록. 실패 시 빈 목록.
     */
    public List<InvestorFlowItem> investorFlowRank(Investor inv, boolean buySide) {
        try {
            throttleFiCall();   // 유량제한 회피 — 호출 간 최소 간격 확보(특히 모의 도메인)
            String query = "FID_COND_MRKT_DIV_CODE=V"            // 이 엔드포인트 고정값
                    + "&FID_COND_SCR_DIV_CODE=16449"            // 이 엔드포인트 고정값
                    + "&FID_INPUT_ISCD=0000"                    // 전체(코스피+코스닥)
                    + "&FID_DIV_CLS_CODE=1"                     // 1: 금액정렬(순매수 거래대금 기준)
                    + "&FID_RANK_SORT_CLS_CODE=" + (buySide ? "0" : "1")  // 0: 순매수상위, 1: 순매도상위
                    + "&FID_ETC_CLS_CODE=" + inv.etcCode;       // 1: 외국인, 2: 기관계

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + FOREIGN_INSTITUTION_TOTAL_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_FOREIGN_INSTITUTION_TOTAL)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<InvestorFlowItem> items = parseInvestorFlow(response.body(), inv);
            if (items.isEmpty() && log.isDebugEnabled()) {
                // 0건이면 원인(빈 output·rt_cd·권한) 파악용으로 원본 응답을 debug로만 남긴다.
                String body = response.body();
                log.debug("KIS 수급 0건 ({} {}) — 원본응답: {}", inv, buySide ? "순매수상위" : "순매도상위",
                        body.length() > 700 ? body.substring(0, 700) : body);
            }
            return items;
        } catch (Exception e) {
            log.warn("KIS 외국인·기관 수급 조회 실패 ({} {}): {}",
                    inv, buySide ? "순매수" : "순매도", e.toString());
            return List.of();
        }
    }

    /**
     * 외국인+기관 동시매매(양매수/양매도) 판정을 위해 전체(FID_ETC_CLS_CODE=0) 순매수/순매도 상위를 조회한다.
     * 한 응답 행에 외국인·기관 순매수 거래대금이 함께 오므로, 두 값을 모두 담아 돌려준다.
     *
     * @param buySide true면 순매수상위, false면 순매도상위
     * @return 외국인·기관 순매수대금을 함께 담은 목록(거래대금은 원으로 환산됨). 실패 시 빈 목록.
     */
    public List<InvestorPairItem> investorFlowDual(boolean buySide) {
        try {
            throttleFiCall();   // 유량제한 회피 — 호출 간 최소 간격 확보(특히 모의 도메인)
            String query = "FID_COND_MRKT_DIV_CODE=V"
                    + "&FID_COND_SCR_DIV_CODE=16449"
                    + "&FID_INPUT_ISCD=0000"
                    + "&FID_DIV_CLS_CODE=1"
                    + "&FID_RANK_SORT_CLS_CODE=" + (buySide ? "0" : "1")
                    + "&FID_ETC_CLS_CODE=0";                 // 0: 전체(외국인·기관 합산 기준 정렬)

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + FOREIGN_INSTITUTION_TOTAL_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_FOREIGN_INSTITUTION_TOTAL)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseInvestorPair(response.body());
        } catch (Exception e) {
            log.warn("KIS 동시매매 수급 조회 실패 ({}): {}", buySide ? "순매수" : "순매도", e.toString());
            return List.of();
        }
    }

    /**
     * 시장별 투자자매매동향(시세, TR FHPTJ04030000) — 시장 '전체'의 외국인·기관 순매수 가집계.
     * 종목별 랭킹과 달리 시장 합계 한 건을 돌려준다. 장중 시간별로 갱신되는 추정치(가집계)다.
     * 표준 호출값은 999/S001(KIS 공식 예제·프로브 실측 확인 — 0001/1001 등은 rt_cd=0이나 값이 전부 0).
     *
     * @param marketLabel  표시·로그용 시장명(시장 전체면 빈 문자열, 코스피/코스닥 분리면 그 라벨)
     * @param fidInputIscd  FID_INPUT_ISCD(시장구분 코드) — 전체 "999", 코스피 "0001", 코스닥 "1001"
     * @param fidInputIscd2 FID_INPUT_ISCD_2(업종구분 코드) — "S001"
     * @param mrktDivCode   FID_COND_MRKT_DIV_CODE(시장분류) — 비면 미전송. 코스피/코스닥 분리 시 "U"(업종) 등.
     * @return 해당 시장 외국인·기관 순매수(원). 실패·거부·빈 응답이면 null.
     */
    public MarketInvestorFlow marketInvestorFlow(String marketLabel, String fidInputIscd, String fidInputIscd2,
                                                 String mrktDivCode) {
        try {
            throttleFiCall();   // 유량제한 회피 — 가집계 계열과 호출 간격 공유
            String query = "FID_INPUT_ISCD=" + fidInputIscd
                    + "&FID_INPUT_ISCD_2=" + fidInputIscd2;
            if (mrktDivCode != null && !mrktDivCode.isBlank()) {
                query += "&FID_COND_MRKT_DIV_CODE=" + mrktDivCode;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + INVESTOR_TIME_BY_MARKET_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_MARKET_INVESTOR_TIME)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            MarketInvestorFlow flow = parseMarketInvestorFlow(response.body(), marketLabel);
            // 전체(빈 라벨) 조회가 0/0·파싱실패면 원인(필드명·시장코드) 파악용으로 원본 응답을 debug로만 남긴다.
            // (코스피/코스닥 분리는 여러 후보를 순차 탐색하며 대부분 0을 돌려주므로 로그를 남기지 않는다.)
            boolean empty = flow == null || (flow.foreignNetWon() == 0 && flow.institutionNetWon() == 0);
            if (empty && marketLabel.isBlank() && log.isDebugEnabled()) {
                String body = response.body();
                log.debug("KIS 시장 수급 전체 0/0 또는 파싱실패 — 원본응답: {}",
                        body.length() > 1500 ? body.substring(0, 1500) : body);
            }
            return flow;
        } catch (Exception e) {
            log.warn("KIS 시장 수급 조회 실패 ({}): {}", marketLabel, e.toString());
            return null;
        }
    }

    /**
     * 외국계 매매종목 가집계(HTS [0430], TR FHKST644100C0) — 외국계 증권사 창구 기준 종목별 외국인 순매수/순매도 상위.
     * 거래소 가집계(foreign-institution-total, 09:30~14:30 4회 입력·동결)와 달리 장중 더 자주 갱신되는 '외국계 창구'
     * 실시간 추정치다(키움 실시간 외국인 수급과 같은 계열). 응답은 금액이 아니라 수량(주)이라
     * 순매수수량(총매수−총매도)×현재가로 원을 근사해 {@link InvestorFlowItem}에 담는다.
     *
     * @param marketDiv 시장구분 — "J":KRX(정규장) / "NX":NXT(애프터마켓). (KIS 문서엔 J만 명시 — NX 지원은 실측 확인 필요)
     * @param buySide   true면 순매수상위(매수순), false면 순매도상위(매도순)
     * @return 외국인(외국계) 순매수금액(원, 근사) 순 종목 목록. 실패 시 빈 목록.
     */
    public List<InvestorFlowItem> foreignMemberEstimate(String marketDiv, boolean buySide) {
        try {
            throttleFiCall();   // 유량제한 회피 — 다른 수급 호출과 같은 간격으로 분산
            String query = "FID_COND_MRKT_DIV_CODE=" + marketDiv
                    + "&FID_COND_SCR_DIV_CODE=16441"           // 이 엔드포인트 고정값
                    + "&FID_INPUT_ISCD=0000"                   // 0000: 전체(코스피+코스닥)
                    + "&FID_RANK_SORT_CLS_CODE=0"              // 0: 금액순
                    + "&FID_RANK_SORT_CLS_CODE_2=" + (buySide ? "0" : "1");  // 0: 매수순, 1: 매도순

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + FRGNMEM_TRADE_ESTIMATE_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_FRGNMEM_TRADE_ESTIMATE)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<InvestorFlowItem> items = parseForeignMemberEstimate(response.body());
            if (items.isEmpty() && log.isDebugEnabled()) {
                // 0건이면 원인(NX 미지원·권한·시간대) 파악용으로 원본 응답을 debug로만 남긴다.
                String body = response.body();
                log.debug("KIS 외국계 가집계 0건 ({} {}) — 원본응답: {}", marketDiv, buySide ? "매수순" : "매도순",
                        body.length() > 700 ? body.substring(0, 700) : body);
            }
            return items;
        } catch (Exception e) {
            log.warn("KIS 외국계 매매종목 가집계 조회 실패 ({} {}): {}", marketDiv, buySide ? "매수" : "매도", e.toString());
            return List.of();
        }
    }

    /**
     * 종목의 KRX 표준 업종명(bstp_kor_isnm)을 조회한다 — 섹터 요약 분류용.
     * 업종은 거래소(KRX/NXT)와 무관하므로 시장구분은 "J"(KRX) 고정으로 충분하다.
     *
     * @return 업종명. 조회 실패·미상이면 빈 문자열.
     */
    public String sectorOf(String code) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + code;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + INQUIRE_PRICE_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_INQUIRE_PRICE)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseSector(response.body());
        } catch (Exception e) {
            log.warn("KIS 업종 조회 실패 ({}): {}", code, e.toString());
            return "";
        }
    }

    /**
     * NXT 호가창의 매도1·매수1 중간값(원)을 조회한다 — 공시 후 주가추적이 NXT 세션 중 체결이 뜸해
     * 체결가가 멈췄을 때, 호가로 움직임을 보완하는 용도다.
     * 호가는 그 시장이 열려 있을 때만 존재하므로 시장구분은 NXT("NX") 고정으로 호출한다
     * (NXT 연장세션엔 KRX가 닫혀 있어 살아있는 호가창은 NXT뿐).
     *
     * @return 매도1·매수1이 모두 유효하면 중간값. 호가 없음·미상장·조회 실패 시 empty(추적을 멈추지 않는다).
     */
    public OptionalLong nxtAskingMidWon(String code) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=NX&FID_INPUT_ISCD=" + code;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + INQUIRE_ASKING_PRICE_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_INQUIRE_ASKING_PRICE)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseAskingMid(response.body());
        } catch (Exception e) {
            log.warn("KIS NXT 호가 조회 실패 ({}): {}", code, e.toString());
            return OptionalLong.empty();
        }
    }

    /**
     * 당일 1분봉을 조회한다 — endHHMMSS(끝시각) 기준 과거로 최대 30개(=30분). 공시 후 주가추적이
     * [공시-2분 ~ 공시+10분] 창(12분)을 한 번에 분석하는 데 쓴다(10초 폴링 대체).
     *
     * 시장구분(marketDiv): "J"(KRX 정규장) / "UN"(통합=KRX+NXT) / "NX"(NXT). KRX만 보면 NXT 연장세션
     * (08:00~09:00·15:30~20:00) 분봉이 비므로, 그 시간대 공시는 "UN"으로 받아야 한다(계정에 UN 권한이
     * 있을 때). 지원 안 되거나 데이터가 없으면 빈 목록 — 호출자가 J로 폴백하거나 분석을 건너뛴다.
     *
     * @param code      종목코드
     * @param endHHMMSS 조회 끝 시각 "HHmmss"
     * @param marketDiv 시장구분 코드(J/UN/NX)
     * @return 1분봉 목록(시각 오름차순 정렬). 조회·파싱 실패 시 빈 목록.
     */
    public List<MinuteCandle> minuteCandles(String code, String endHHMMSS, String marketDiv) {
        try {
            String query = "FID_ETC_CLS_CODE="
                    + "&FID_COND_MRKT_DIV_CODE=" + marketDiv
                    + "&FID_INPUT_ISCD=" + code
                    + "&FID_INPUT_HOUR_1=" + endHHMMSS
                    + "&FID_PW_DATA_INCU_YN=N";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + INQUIRE_TIME_CHART_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_TIME_CHART)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseMinuteCandles(response.body());
        } catch (Exception e) {
            log.warn("KIS 분봉 조회 실패 ({}): {}", code, e.toString());
            return List.of();
        }
    }

    /** 현재가 조회 응답에서 업종명(bstp_kor_isnm)만 추출한다. (네트워크 분리 — 테스트용 패키지 가시성) */
    static String parseSector(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                // 비정상 응답을 조용히 삼키면 전 종목이 '미분류'로 떨어지는 원인을 못 찾는다 — rt_cd/msg를 드러낸다.
                // (예: EGW00201 초당 거래건수 초과 = 유량제한, 권한 미보유 등)
                log.warn("KIS 업종 조회 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return "";
            }
            return root.path("output").path("bstp_kor_isnm").asText("").trim();
        } catch (Exception e) {
            log.warn("KIS 업종 파싱 실패: {}", e.toString());
            return "";
        }
    }

    /**
     * 호가 조회 응답에서 매도1호가(askp1)·매수1호가(bidp1)를 뽑아 중간값(원)을 계산한다.
     * 둘 다 양수일 때만 의미가 있다 — 한쪽이라도 0(호가 비어있음·장 닫힘·미상장)이면 empty.
     * (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static OptionalLong parseAskingMid(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 호가 조회 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return OptionalLong.empty();
            }
            JsonNode out = root.path("output1");
            long ask = parseLong(out.path("askp1").asText());
            long bid = parseLong(out.path("bidp1").asText());
            if (ask <= 0 || bid <= 0) return OptionalLong.empty();
            return OptionalLong.of((ask + bid) / 2);
        } catch (Exception e) {
            log.warn("KIS 호가 파싱 실패: {}", e.toString());
            return OptionalLong.empty();
        }
    }

    /**
     * 분봉 응답(output2)을 시각 오름차순 MinuteCandle 목록으로 파싱한다. KIS는 최신→과거 순으로 주므로
     * 마지막에 뒤집어 오름차순으로 돌려준다. rt_cd!="0"이면 빈 목록. (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static List<MinuteCandle> parseMinuteCandles(String json) {
        List<MinuteCandle> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 분봉 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return result;
            }
            for (JsonNode n : root.path("output2")) {
                String hms = n.path("stck_cntg_hour").asText("");
                if (hms.length() < 6) continue;
                long close = parseLong(n.path("stck_prpr").asText());
                if (close <= 0) continue;   // 거래 없는 빈 봉(0)은 버린다
                LocalTime time = LocalTime.of(
                        Integer.parseInt(hms.substring(0, 2)),
                        Integer.parseInt(hms.substring(2, 4)),
                        Integer.parseInt(hms.substring(4, 6)));
                result.add(new MinuteCandle(time,
                        parseLong(n.path("stck_oprc").asText()),
                        parseLong(n.path("stck_hgpr").asText()),
                        parseLong(n.path("stck_lwpr").asText()),
                        close));
            }
            // KIS는 최신→과거 순. 분석은 시간순이 편하므로 오름차순으로 뒤집는다.
            java.util.Collections.reverse(result);
        } catch (Exception e) {
            log.warn("KIS 분봉 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /** 등락률순위 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록. (네트워크 분리 — 테스트용 패키지 가시성) */
    static List<VolumeRankItem> parseFluctuationRank(String json) {
        List<VolumeRankItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                String rtCd = root.path("rt_cd").asText();
                String msg = root.path("msg1").asText();
                // 시장구분 거부(rt_cd=2, INVALID FID_COND_MRKT_DIV_CODE)는 설정 오류라 영원히 빈 결과만 내므로
                // error로 격상해 로그에서 눈에 띄게 한다(예: KIS_MARKET_DIV_CODE=UN 미허용 계정).
                if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
                    log.error("KIS 등락률순위 시장구분 거부: rt_cd={} msg={} — KIS_MARKET_DIV_CODE 설정 확인(J/NX/UN)",
                            rtCd, msg);
                } else {
                    log.warn("KIS 등락률순위 비정상 응답: rt_cd={} msg={}", rtCd, msg);
                }
                return result;
            }
            for (JsonNode n : root.path("output")) {
                String code = n.path("stck_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new VolumeRankItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("stck_prpr").asText()),
                        parseDouble(n.path("prdy_ctrt").asText()),
                        parseLong(n.path("acml_vol").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 등락률순위 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 거래대금순위 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록. (네트워크 분리 — 테스트용 패키지 가시성)
     * volume-rank의 종목코드 필드는 mksc_shrn_iscd, 거래대금은 acml_tr_pbmn(원).
     */
    static List<TradingValueItem> parseVolumeRank(String json) {
        List<TradingValueItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                String rtCd = root.path("rt_cd").asText();
                String msg = root.path("msg1").asText();
                if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
                    log.error("KIS 거래대금순위 시장구분 거부: rt_cd={} msg={} — 시장구분(J/NX/UN) 확인", rtCd, msg);
                } else {
                    log.warn("KIS 거래대금순위 비정상 응답: rt_cd={} msg={}", rtCd, msg);
                }
                return result;
            }
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new TradingValueItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("acml_tr_pbmn").asText()),
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 거래대금순위 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 외국인·기관 매매종목가집계 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록. (네트워크 분리 — 테스트용 패키지 가시성)
     * 종목코드는 mksc_shrn_iscd, 순매수 거래대금은 투자자별 필드(외국인 frgn_ntby_tr_pbmn / 기관 orgn_ntby_tr_pbmn).
     */
    static List<InvestorFlowItem> parseInvestorFlow(String json, Investor inv) {
        List<InvestorFlowItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                String rtCd = root.path("rt_cd").asText();
                String msg = root.path("msg1").asText();
                if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
                    log.error("KIS 외국인·기관 수급 거부: rt_cd={} msg={} — 권한/도메인(실전) 확인", rtCd, msg);
                } else {
                    log.warn("KIS 외국인·기관 수급 비정상 응답: rt_cd={} msg={}", rtCd, msg);
                }
                return result;
            }
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new InvestorFlowItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path(inv.amountField).asText()) * NTBY_PBMN_UNIT_WON,  // 백만원 → 원
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 외국인·기관 수급 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 동시매매 판정용 — 한 행에서 외국인·기관 순매수 거래대금을 함께 파싱한다. rt_cd!="0"이면 빈 목록.
     * 거래대금은 백만원→원으로 환산해 담는다. (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static List<InvestorPairItem> parseInvestorPair(String json) {
        List<InvestorPairItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                String rtCd = root.path("rt_cd").asText();
                String msg = root.path("msg1").asText();
                if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
                    log.error("KIS 동시매매 수급 거부: rt_cd={} msg={} — 권한/도메인(실전) 확인", rtCd, msg);
                } else {
                    log.warn("KIS 동시매매 수급 비정상 응답: rt_cd={} msg={}", rtCd, msg);
                }
                return result;
            }
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new InvestorPairItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("frgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,  // 백만원 → 원
                        parseLong(n.path("orgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 동시매매 수급 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 시장별 투자자매매동향(시세) 응답에서 시장 전체 외국인·기관 순매수 거래대금을 읽는다.
     * 시계열 output 중 최신 누적 행(output[0] 가정 — ⚠ 행 정렬은 첫 호출 로그로 검증)에서
     * frgn/orgn/prsn_ntby_tr_pbmn을 백만원→원으로 환산({@link #NTBY_PBMN_UNIT_WON}). rt_cd!="0"·빈 응답이면 null.
     * (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static MarketInvestorFlow parseMarketInvestorFlow(String json, String marketLabel) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 시장 수급({}) 비정상 응답: rt_cd={} msg={}",
                        marketLabel, root.path("rt_cd").asText(), root.path("msg1").asText());
                return null;
            }
            JsonNode out = root.path("output");
            JsonNode row = out.isArray() ? (out.isEmpty() ? null : out.get(0)) : out;
            if (row == null || row.isMissingNode()) return null;
            long frgn = parseLong(row.path("frgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON;
            long orgn = parseLong(row.path("orgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON;
            long prsn = parseLong(row.path("prsn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON;   // 개인 순매수(원)
            return new MarketInvestorFlow(marketLabel, frgn, orgn, prsn);
        } catch (Exception e) {
            log.warn("KIS 시장 수급({}) 파싱 실패: {}", marketLabel, e.toString());
            return null;
        }
    }

    /**
     * 외국계 매매종목 가집계(FHKST644100C0) 응답 파싱. rt_cd!="0"이면 빈 목록.
     * 종목코드 stck_shrn_iscd, 종목명 hts_kor_isnm. 순매수수량 = 외국계총매수(glob_total_shnu_qty) − 외국계총매도(glob_total_seln_qty),
     * 금액(원) ≈ 순매수수량 × 현재가(stck_prpr). (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static List<InvestorFlowItem> parseForeignMemberEstimate(String json) {
        List<InvestorFlowItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                String rtCd = root.path("rt_cd").asText();
                String msg = root.path("msg1").asText();
                if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
                    log.error("KIS 외국계 매매종목 가집계 거부: rt_cd={} msg={} — 권한/도메인(실전)·시장구분(NX) 확인", rtCd, msg);
                } else {
                    log.warn("KIS 외국계 매매종목 가집계 비정상 응답: rt_cd={} msg={}", rtCd, msg);
                }
                return result;
            }
            for (JsonNode n : root.path("output")) {
                String code = n.path("stck_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                long netQty = parseLong(n.path("glob_total_shnu_qty").asText())
                        - parseLong(n.path("glob_total_seln_qty").asText());   // 외국계 순매수수량
                long price = parseLong(n.path("stck_prpr").asText());
                result.add(new InvestorFlowItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        netQty * price,                                        // 순매수수량 × 현재가 ≈ 순매수금액(원)
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 외국계 매매종목 가집계 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 종목별 투자자 '확정' 순매수 거래대금(원) — inquire-investor(FHKST01010900) output[0](당일)에서 외국인·기관을 읽는다.
     * 가집계(FHPTJ04400000, 추정·14:30 동결)와 달리 증권사 화면의 마감 후 확정 수급과 같다. 마감 후 호출해야 당일 확정값.
     * 한 응답에 외국인·기관이 함께 오므로, 수급 랭킹을 확정치로 재구성할 때 종목당 1회 호출이면 된다.
     *
     * @param code      종목코드(6자리)
     * @param marketDiv 시장구분 — "J":KRX(정규장 확정) / "NX":NXT(애프터마켓 최종). (KIS 공식: FID_COND_MRKT_DIV_CODE J/NX만 허용)
     * @return 외국인·기관 확정 순매수대금(원). 실패·빈 응답·rt_cd!="0"이면 null.
     */
    public InvestorConfirmed inquireInvestorConfirmed(String code, String marketDiv) {
        try {
            throttleFiCall();   // 유량제한 회피 — 가집계와 같은 간격으로 분산(단일 폴러 스레드)
            String query = "FID_COND_MRKT_DIV_CODE=" + marketDiv + "&FID_INPUT_ISCD=" + code;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + INQUIRE_INVESTOR_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_INQUIRE_INVESTOR)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseInvestorConfirmed(response.body());
        } catch (Exception e) {
            log.warn("KIS 종목별 투자자 확정 수급 조회 실패 ({}): {}", code, e.toString());
            return null;
        }
    }

    /**
     * inquire-investor 응답에서 당일(output[0]) 외국인·기관 순매수 거래대금을 읽는다.
     * 거래대금 단위는 가집계와 동일하게 백만원으로 보고 원으로 환산한다({@link #NTBY_PBMN_UNIT_WON}).
     * rt_cd!="0"이거나 output이 비면 null. (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static InvestorConfirmed parseInvestorConfirmed(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 종목별 투자자 확정 수급 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return null;
            }
            JsonNode out = root.path("output");
            if (!out.isArray() || out.isEmpty()) return null;
            JsonNode row = out.get(0);   // 최신 영업일(마감 후=당일 확정)
            return new InvestorConfirmed(
                    row.path("stck_bsop_date").asText("").trim(),
                    parseLong(row.path("frgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,
                    parseLong(row.path("orgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON);
        } catch (Exception e) {
            log.warn("KIS 종목별 투자자 확정 수급 파싱 실패: {}", e.toString());
            return null;
        }
    }

    /**
     * 직전 가집계 호출 이후 {@link #FI_CALL_MIN_INTERVAL_MS}가 지나도록 대기한다 — 연속 호출의 유량제한(EGW00201) 회피.
     * 폴러 단일 스레드에서만 호출되고, 다른 스케줄 작업(급등 폴링 등)은 비활성/한가하므로 잠깐의 sleep은 안전하다.
     */
    private void throttleFiCall() {
        long wait = FI_CALL_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFiCallAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastFiCallAt = System.currentTimeMillis();
    }

    /**
     * 국내 휴장일 조회(CTCA0903R) — 기준일부터 약 한 달간의 개장 여부(opnd_yn)를 받아 '휴장'인 날짜를 돌려준다.
     * 공휴일·임시공휴일·연말 폐장일 등 KRX가 실제로 쉬는 날을 실측으로 얻는 용도다(주말은 캘린더가 따로 거른다).
     * 단일 페이지(~1개월)면 당일 게이트에 충분하다. 실패·거부 시 빈 목록(주말만 거르는 기존 동작으로 폴백).
     *
     * @param from 기준일(보통 오늘, KST)
     * @return 기준일 이후 휴장일(개장 안 하는 날) 목록. 실패 시 빈 목록.
     */
    public List<LocalDate> marketClosedDays(LocalDate from) {
        try {
            String query = "BASS_DT=" + from.format(YYYYMMDD) + "&CTX_AREA_NK=&CTX_AREA_FK=";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + HOLIDAY_PATH + "?" + query))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token())
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", TR_HOLIDAY)
                    .header("custtype", "P")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 휴장일 조회 거부 — 주말만 거른다: {}", root.path("msg1").asText());
                return List.of();
            }
            List<LocalDate> closed = new ArrayList<>();
            for (JsonNode r : root.path("output")) {
                String dt = r.path("bass_dt").asText("");
                if (dt.length() == 8 && "N".equals(r.path("opnd_yn").asText(""))) {
                    try {
                        closed.add(LocalDate.parse(dt, YYYYMMDD));
                    } catch (Exception ignore) {
                        // 형식 이상 행은 건너뛴다(부분 파싱 허용)
                    }
                }
            }
            log.info("KIS 휴장일 조회 {}건 (기준 {})", closed.size(), from);
            return closed;
        } catch (Exception e) {
            log.warn("KIS 휴장일 조회 실패 — 주말만 거른다: {}", e.toString());
            return List.of();
        }
    }

    // ---- 토큰 관리 ----

    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpiry.minus(TOKEN_REFRESH_MARGIN))) {
            return token;
        }
        issueToken();
        return token;
    }

    private void issueToken() throws Exception {
        String body = mapper.createObjectNode()
                .put("grant_type", "client_credentials")
                .put("appkey", appKey)
                .put("appsecret", appSecret)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + TOKEN_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        String accessToken = root.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("KIS 토큰 발급 실패: " + response.body());
        }
        long expiresInSec = root.path("expires_in").asLong(86400);
        this.token = accessToken;
        this.tokenExpiry = Instant.now().plusSeconds(expiresInSec);
        saveTokenToFile();
        log.info("KIS 토큰 발급 완료 (만료 {})", tokenExpiry);
    }

    private void loadTokenFromFile() {
        try {
            if (!Files.exists(tokenFile)) return;
            List<String> lines = Files.readAllLines(tokenFile, StandardCharsets.UTF_8);
            if (lines.size() < 2) return;
            Instant expiry = Instant.ofEpochSecond(Long.parseLong(lines.get(1).trim()));
            if (Instant.now().isBefore(expiry.minus(TOKEN_REFRESH_MARGIN))) {
                this.token = lines.get(0).trim();
                this.tokenExpiry = expiry;
                log.info("KIS 토큰 파일에서 재사용 (만료 {})", expiry);
            }
        } catch (Exception e) {
            log.warn("KIS 토큰 파일 읽기 실패 — 새로 발급한다: {}", e.toString());
        }
    }

    private void saveTokenToFile() {
        try {
            Files.write(tokenFile,
                    List.of(token, Long.toString(tokenExpiry.getEpochSecond())),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("KIS 토큰 파일 저장 실패 (재시작 시 재발급됨): {}", e.toString());
        }
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        s = s.replaceAll("[,\\s]", "");
        if (s.isEmpty() || "-".equals(s)) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        if (s == null) return 0;
        s = s.replaceAll("[,\\s]", "");
        if (s.isEmpty() || "-".equals(s)) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
