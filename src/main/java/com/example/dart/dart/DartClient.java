package com.example.dart.dart;

import com.example.dart.model.Disclosure;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);
    private static final String BASE_URL = "https://opendart.fss.or.kr/api";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public DartClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 오늘자 공시 목록을 조회한다.
     *
     * corpCls  : 콤마 구분 시장 코드 (Y=코스피, K=코스닥). 빈 값이면 전체.
     * pblntfTy : 콤마 구분 공시유형 (B=주요사항보고, I=거래소공시). 빈 값이면 전체.
     *
     * DART API는 두 파라미터 모두 단일 값만 허용하므로, 복수 지정 시 조합별로 호출 후
     * rceptNo 기준으로 중복 없이 병합한다.
     */
    public List<Disclosure> fetchRecent(String corpCls, String pblntfTy) {
        String today = LocalDate.now().format(DATE_FMT);

        String[] clsTokens = splitTokens(corpCls);
        String[] tyTokens  = splitTokens(pblntfTy);

        Map<String, Disclosure> merged = new LinkedHashMap<>();
        for (String cls : clsTokens) {
            for (String ty : tyTokens) {
                for (Disclosure d : fetchOne(today, cls, ty)) {
                    merged.putIfAbsent(d.rceptNo(), d);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    public byte[] fetchDocument(String rceptNo) {
        String url = BASE_URL + "/document.xml"
                + "?crtfc_key=" + enc(apiKey)
                + "&rcept_no=" + enc(rceptNo);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("application/json")) {
                String body = new String(response.body());
                log.error("document.xml 요청 실패 (JSON 응답): {}", body);
                throw new DartException("document.xml 조회 실패: " + body);
            }

            return response.body();
        } catch (DartException e) {
            throw e;
        } catch (Exception e) {
            throw new DartException("document.xml 다운로드 실패: " + rceptNo, e);
        }
    }

    // ---- private helpers ----

    private List<Disclosure> fetchOne(String today, String corpCls, String pblntfTy) {
        String url = BASE_URL + "/list.json"
                + "?crtfc_key=" + enc(apiKey)
                + "&bgn_de=" + today
                + "&end_de=" + today
                + "&page_no=1"
                + "&page_count=100"
                + "&sort=date&sort_mth=desc"   // 최신 공시가 항상 100건 안에 포함
                + (corpCls  != null ? "&corp_cls="  + enc(corpCls)  : "")
                + (pblntfTy != null ? "&pblntf_ty=" + enc(pblntfTy) : "");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            String status = root.path("status").asText();

            switch (status) {
                case "000" -> {
                    JsonNode listNode = root.path("list");
                    List<Disclosure> result = new ArrayList<>();
                    for (JsonNode node : listNode) {
                        result.add(mapper.treeToValue(node, Disclosure.class));
                    }
                    log.info("공시 조회 완료 (corp_cls={}, pblntf_ty={}, {}건)", corpCls, pblntfTy, result.size());
                    return result;
                }
                case "013" -> {
                    log.info("공시 없음 (corp_cls={}, pblntf_ty={})", corpCls, pblntfTy);
                    return Collections.emptyList();
                }
                case "020" -> {
                    log.warn("DART API 호출 한도 초과 (status=020)");
                    return Collections.emptyList();
                }
                default -> {
                    log.error("DART API 오류: status={}, message={}", status, root.path("message").asText());
                    return Collections.emptyList();
                }
            }
        } catch (Exception e) {
            log.error("DART 공시 목록 조회 실패 (corp_cls={}, pblntf_ty={})", corpCls, pblntfTy, e);
            return Collections.emptyList();
        }
    }

    /** 콤마 구분 문자열을 토큰 배열로 분리. 빈 값이면 {null} 반환(파라미터 없이 전체 조회). */
    private static String[] splitTokens(String value) {
        if (value == null || value.isBlank()) return new String[]{null};
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
