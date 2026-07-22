package com.example.dart.disclosure.infra;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.common.infra.TrustStores;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KIND 공시 뷰어에서 본문 HTML과 종목코드를 가져온다.
 *
 * KIND는 거래소 공시를 DART {@code document.xml}(목록보다 수 분~수 시간 늦게 공개됨, status 014)보다
 * 먼저 게시하므로, KIND 선행 공시는 KIND 본문에서 직접 규모(계약금액·매출액대비)를 뽑아 즉시 보강한다.
 *
 * 흐름(브라우저 동작을 그대로 재현):
 *  1) 뷰어 페이지 {@code disclsviewer.do?method=search&acptno=…}
 *     → {@code <select id="mainDoc">} 옵션값("docNo|lstYn")에서 docNo, 제목줄("회사 (123456)")에서 종목코드.
 *  2) {@code disclsviewer.do?method=searchContents&docNo=…} 응답의
 *     {@code parent.setPath('','<본문URL>', …)} 에서 본문 .htm 절대 URL.
 *  3) 본문 .htm(보통 EUC-KR) 바이트를 그대로 반환 — 평문화·파싱은 DocumentParser가 담당.
 *
 * 회사망 SSL 검사 프록시 때문에 새 HttpClient엔 TrustStores.systemDefault()를 적용한다.
 */
public class KindDocumentClient {

    private static final String BASE = "https://kind.krx.co.kr";
    private static final String VIEWER = BASE + "/common/disclsviewer.do?method=search&acptno=";
    private static final String CONTENTS = BASE + "/common/disclsviewer.do?method=searchContents&docNo=";

    /** {@code <option value='20260617000606|Y' …>} 에서 첫 본문 docNo. */
    private static final Pattern MAIN_DOC = Pattern.compile(
            "id=\"mainDoc\"[^>]*>.*?<option\\s+value=['\"](\\d+)\\|", Pattern.DOTALL);
    /** 제목줄 "우진 (105840)" 에서 6자리 종목코드. */
    private static final Pattern STOCK_CODE = Pattern.compile("\\((\\d{6})\\)");
    /** {@code parent.setPath('','https://…/91370.htm', …)} 에서 본문 URL (2번째 인자). */
    private static final Pattern DOC_PATH = Pattern.compile(
            "setPath\\(\\s*'[^']*'\\s*,\\s*'([^']+)'");

    private final HttpClient httpClient;

    public KindDocumentClient() {
        this.httpClient = TrustStores.newHttpClientBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** acptNo 공시의 본문 HTML 바이트 + 종목코드. 실패는 호출자에게 전파(보강 재시도용). */
    public KindDocument fetch(String acptNo) throws Exception {
        String viewer = getString(VIEWER + acptNo);
        String docNo = extractDocNo(viewer);
        if (docNo == null) {
            throw new IllegalStateException("KIND 뷰어에서 docNo를 찾지 못함 (acptNo=" + acptNo + ")");
        }
        String stockCode = extractStockCode(viewer);

        String contents = getString(CONTENTS + docNo + "&acptNo=" + acptNo);
        String docUrl = extractDocUrl(contents);
        if (docUrl == null) {
            throw new IllegalStateException("KIND 본문 경로(setPath)를 찾지 못함 (docNo=" + docNo + ")");
        }

        byte[] body = getBytes(docUrl);
        return new KindDocument(body, stockCode);
    }

    /** docNo·종목코드·본문 URL 추출만 단위 테스트할 수 있게 분리(네트워크 없음). */
    static String extractDocNo(String viewerHtml) {
        Matcher m = MAIN_DOC.matcher(viewerHtml);
        return m.find() ? m.group(1) : null;
    }

    static String extractStockCode(String viewerHtml) {
        Matcher m = STOCK_CODE.matcher(viewerHtml);
        return m.find() ? m.group(1) : null;
    }

    static String extractDocUrl(String contentsHtml) {
        Matcher m = DOC_PATH.matcher(contentsHtml);
        return m.find() ? m.group(1) : null;
    }

    private String getString(String url) throws Exception {
        // 우리가 뽑는 값(docNo·종목코드·본문경로)은 모두 ASCII/숫자라, 본문이 EUC-KR이어도 해당 부분은 안 깨진다.
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", HttpJson.UA_BROWSER)
                .header("Referer", BASE + "/common/disclsviewer.do")
                .GET()
                .build();
        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("KIND 응답 코드 " + res.statusCode() + " (" + url + ")");
        }
        return res.body();
    }

    /** KIND 본문 HTML 바이트 + 뷰어에서 읽은 종목코드(없으면 null). */
    public record KindDocument(byte[] bodyHtml, String stockCode) {}
}
