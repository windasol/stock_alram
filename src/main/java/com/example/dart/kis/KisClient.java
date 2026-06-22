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
import java.util.ArrayList;
import java.util.List;

/**
 * 한국투자증권(KIS) Open API 클라이언트 — 급등 정찰에 쓰는 "등락률순위" 조회만 담당한다.
 *
 * 인증: appkey/appsecret(시스템 환경변수에서 주입)로 OAuth 토큰을 발급받아 Bearer로 호출한다.
 * 토큰은 유효기간 1일이고 잦은 재발급은 한도(분당 1회)·알림톡 발송을 유발하므로, 파일에 영속화해
 * 재시작 후에도 살아있는 토큰을 재사용한다.
 *
 * 실전 도메인만 사용한다(모의투자는 순위분석 미지원). 시세·순위 조회는 계좌번호가 필요 없다.
 */
public class KisClient {

    private static final Logger log = LoggerFactory.getLogger(KisClient.class);

    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final String FLUCTUATION_RANK_PATH = "/uapi/domestic-stock/v1/ranking/fluctuation";
    private static final String TR_FLUCTUATION_RANK = "FHPST01700000";
    private static final String INQUIRE_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String TR_INQUIRE_PRICE = "FHKST01010100";

    /** 토큰 만료 이만큼 전에 미리 재발급한다. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(10);

    private final String appKey;
    private final String appSecret;
    /** 등락률순위 조회 시장구분 — J: KRX, NX: NXT, UN: 통합(KRX+NXT). NXT 거래시간(연장세션)까지 잡으려면 UN. */
    private final String marketDivCode;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path tokenFile;

    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public KisClient(String appKey, String appSecret, String marketDivCode, Path tokenFile) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.marketDivCode = (marketDivCode == null || marketDivCode.isBlank()) ? "J" : marketDivCode;
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

    /**
     * 등락률순위 — 전일 종가 대비 등락률(prdy_ctrt) 상위 종목을 내림차순으로 받아온다(최대 30건).
     * 시장구분(marketDivCode)에 따라 KRX·NXT·통합에서 조회한다. 거래량·거래대금·주가 하한은 두지 않고
     * "오늘 많이 오른 종목"만 본다 — 임계 필터는 폴러가 등락률로만 적용한다.
     *
     * @return 급등 판단에 쓸 값만 담은 목록. 실패 시 빈 목록.
     */
    public List<VolumeRankItem> topGainers() {
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
                    .uri(URI.create(BASE_URL + FLUCTUATION_RANK_PATH + "?" + query))
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
     * 종목의 KRX 표준 업종명(bstp_kor_isnm)을 조회한다 — 섹터 요약 분류용.
     * 업종은 거래소(KRX/NXT)와 무관하므로 시장구분은 "J"(KRX) 고정으로 충분하다.
     *
     * @return 업종명. 조회 실패·미상이면 빈 문자열.
     */
    public String sectorOf(String code) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + code;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + INQUIRE_PRICE_PATH + "?" + query))
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

    /** 현재가 조회 응답에서 업종명(bstp_kor_isnm)만 추출한다. (네트워크 분리 — 테스트용 패키지 가시성) */
    static String parseSector(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) return "";
            return root.path("output").path("bstp_kor_isnm").asText("").trim();
        } catch (Exception e) {
            log.warn("KIS 업종 파싱 실패: {}", e.toString());
            return "";
        }
    }

    /** 등락률순위 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록. (네트워크 분리 — 테스트용 패키지 가시성) */
    static List<VolumeRankItem> parseFluctuationRank(String json) {
        List<VolumeRankItem> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 등락률순위 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
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
                .uri(URI.create(BASE_URL + TOKEN_PATH))
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
