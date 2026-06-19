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
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);
    private static final String BASE_URL = "https://opendart.fss.or.kr/api";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    /**
     * 같은 회사 공시가 연속으로 와도 재무 API를 반복 호출하지 않도록 매출액을 회사별로 캐시.
     * 단, 새 사업보고서·정정(소급재작성)이 하루 안에 반영되도록 조회일을 함께 저장해 매일 갱신한다.
     * 만료가 없으면 장기 실행 인스턴스가 옛 매출(예: 사업보고서 제출 전 담긴 전년도 값)을 계속 우려먹는다.
     */
    private final Map<String, CachedRevenue> revenueCache = new ConcurrentHashMap<>();

    /** 매출액 캐시 항목 — 조회일(asOf)이 오늘이 아니면 만료로 보고 다시 조회한다. */
    private record CachedRevenue(LocalDate asOf, OptionalLong value) {}

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
            return interpretDocumentResponse(response.body(), rceptNo);
        } catch (DartException e) {
            throw e;
        } catch (Exception e) {
            throw new DartException("document.xml 다운로드 실패: " + rceptNo, e);
        }
    }

    /**
     * document.xml 응답 바이트를 해석한다.
     *
     * 성공 응답은 ZIP 바이너리(PK 시그니처)다 — content-type은 부정확하므로
     * (성공=application/x-msdownload, 에러=application/xml) 본문 시그니처로 판별한다.
     * ZIP이 아니면 DART 에러 응답(XML)이므로 {@code <status>}를 파싱해 일시·영구를 구분한다.
     *
     * @return 정상 ZIP 바이트
     * @throws DocumentNotReadyException status 014(원문 미공개) — 재조회로 회복 가능
     * @throws DartException 그 외 에러
     */
    static byte[] interpretDocumentResponse(byte[] body, String rceptNo) {
        if (looksLikeZip(body)) {
            return body;
        }

        String text = new String(body, StandardCharsets.UTF_8);
        String status = extractTag(text, "status");
        String message = extractTag(text, "message");

        // 014: 원문이 아직 공개되지 않음 — 영구 실패가 아니라 잠시 뒤 재조회하면 되는 상태.
        if ("014".equals(status)) {
            throw new DocumentNotReadyException(
                    "원문 미공개 (status=014, " + rceptNo + "): " + message);
        }

        log.error("document.xml 조회 실패 (status={}): {}", status, message.isEmpty() ? text : message);
        throw new DartException("document.xml 조회 실패 (status=" + status + "): " + message);
    }

    /** 응답 바이트가 ZIP 로컬 파일 헤더 시그니처(PK\x03\x04)로 시작하는지 확인. */
    private static boolean looksLikeZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    /** 단순 XML 태그 본문 추출: {@code <tag>값</tag>} → "값". 없으면 빈 문자열. */
    private static String extractTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 최근 연간 매출액(원)을 OpenDART 재무 API(단일회사 주요계정)에서 조회한다.
     *
     * 공시 원문(document.xml)은 게시 직후 한동안 받을 수 없지만(status 014), 재무 API는 lag가 없어
     * 계약금액 대비 매출 비율을 즉시 계산할 수 있다. 연결재무제표(CFS) 우선, 없으면 별도(OFS).
     * 올해 사업보고서는 아직 없으므로 직전연도부터 한 해 전까지 차례로 시도한다.
     */
    public OptionalLong recentRevenueWon(String corpCode) {
        if (corpCode == null || corpCode.isBlank()) return OptionalLong.empty();
        LocalDate today = LocalDate.now();
        CachedRevenue cached = revenueCache.get(corpCode);
        if (cached != null && cached.asOf().equals(today)) {
            return cached.value();
        }
        // 직전연도 사업보고서부터 시도 — 아직 미제출이면 그 전년도로 폴백.
        int year = today.getYear();
        OptionalLong rev = OptionalLong.empty();
        for (int y = year - 1; y >= year - 2; y--) {
            OptionalLong r = revenueFor(corpCode, y);
            if (r.isPresent()) {
                rev = r;
                break;
            }
        }
        revenueCache.put(corpCode, new CachedRevenue(today, rev));
        return rev;
    }

    private OptionalLong revenueFor(String corpCode, int year) {
        String url = BASE_URL + "/fnlttSinglAcnt.json"
                + "?crtfc_key=" + enc(apiKey)
                + "&corp_code=" + enc(corpCode)
                + "&bsns_year=" + year
                + "&reprt_code=11011";   // 11011 = 사업보고서(연간)
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (!"000".equals(root.path("status").asText())) return OptionalLong.empty();

            Long cfs = null, ofs = null;   // 연결 / 별도
            for (JsonNode n : root.path("list")) {
                String acc = n.path("account_nm").asText();
                if (!(acc.contains("매출액") || acc.equals("영업수익"))) continue;
                String amt = n.path("thstrm_amount").asText().replaceAll("[,\\s]", "");
                if (amt.isEmpty() || "-".equals(amt)) continue;
                try {
                    long v = Long.parseLong(amt);
                    if ("CFS".equals(n.path("fs_div").asText())) {
                        if (cfs == null) cfs = v;
                    } else if (ofs == null) {
                        ofs = v;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            Long pick = cfs != null ? cfs : ofs;
            if (pick != null) {
                log.info("매출액 조회 ({}년, corp_code={}): {}원", year, corpCode, pick);
                return OptionalLong.of(pick);
            }
            return OptionalLong.empty();
        } catch (Exception e) {
            log.warn("매출액 조회 실패 (corp_code={}, year={}): {}", corpCode, year, e.toString());
            return OptionalLong.empty();
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
