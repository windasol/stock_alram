package com.example.dart.dart;

import com.example.dart.model.Disclosure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public List<Disclosure> fetchRecent(String corpCls) {
        String today = LocalDate.now().format(DATE_FMT);
        String url = BASE_URL + "/list.json"
                + "?crtfc_key=" + apiKey
                + "&bgn_de=" + today
                + "&end_de=" + today
                + "&page_no=1"
                + "&page_count=100"
                + (corpCls != null && !corpCls.isBlank() ? "&corp_cls=" + corpCls : "");

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
                    return result;
                }
                case "013" -> {
                    log.debug("오늘 공시 데이터 없음");
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
            log.error("DART 공시 목록 조회 실패", e);
            return Collections.emptyList();
        }
    }

    public byte[] fetchDocument(String rceptNo) {
        String url = BASE_URL + "/document.xml"
                + "?crtfc_key=" + apiKey
                + "&rcept_no=" + rceptNo;

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
}
