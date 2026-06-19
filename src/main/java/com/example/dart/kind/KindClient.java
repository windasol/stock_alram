package com.example.dart.kind;

import com.example.dart.util.DisclosureKeys;
import com.example.dart.util.TrustStores;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KIND(한국거래소 공시시스템) 오늘의공시 클라이언트.
 * 거래소 공시는 KIND에 먼저 게시되고 DART 목록 API에는 수 분 늦게 반영되는 경우가 많다 —
 * 이 클라이언트가 봇에서 가장 빠른 공시 소스다.
 *
 * 공식 API가 아니라 화면용 AJAX 엔드포인트를 호출하므로 브라우저형 헤더가 필요하고,
 * 응답은 HTML 테이블 조각이다 (행: 시간 | 시장아이콘+회사명 | 제목 | 제출인 | 차트).
 */
public class KindClient {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ENDPOINT = "https://kind.krx.co.kr/disclosure/todaydisclosure.do";

    /** onclick="openDisclsViewer('20260612000528','')" 에서 접수번호 추출. */
    private static final Pattern ACPT_NO_PATTERN = Pattern.compile("openDisclsViewer\\('(\\d+)'");

    private final HttpClient httpClient;

    public KindClient() {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(TrustStores.systemDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 오늘자 공시 목록 (최신순).
     * 실패는 호출자에게 전파한다 — 폴러가 연속 실패를 세서 백오프한다.
     */
    public List<KindDisclosure> fetchToday() throws Exception {
        String form = "method=searchTodayDisclosureSub&currentPageSize=100&pageIndex=1"
                + "&orderMode=0&orderStat=D&forward=todaydisclosure_sub&chose=S&todayFlag=Y"
                + "&selDate=" + LocalDate.now(KST);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", ENDPOINT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("KIND 응답 코드 " + response.statusCode());
        }
        return parse(response.body());
    }

    /**
     * 같은 공시의 KIND 접수번호(acptNo)를 회사명+제목으로 찾는다 — DART 폴러가 먼저 잡은 계약을
     * KIND 뷰어 본문으로 즉시 보강할 때, DART rcept_no는 KIND acptNo와 다르므로(접수번호 체계가 별개)
     * 교차중복과 동일한 정규화 키({@link DisclosureKeys})로 오늘 목록에서 매칭한다.
     *
     * @param date 공시 게시일(yyyyMMdd) — DART rcept_dt. 같은 날짜를 양쪽 키에 동일하게 써서 회사·제목만으로 비교.
     * @return 일치하는 acptNo (아직 KIND 미게시 등으로 없으면 empty). 네트워크 실패는 호출자에게 전파.
     */
    public Optional<String> findAcptNo(String date, String company, String title) throws Exception {
        String target = DisclosureKeys.of(date, company, title);
        for (KindDisclosure d : fetchToday()) {
            if (DisclosureKeys.of(date, d.company(), d.title()).equals(target)) {
                return Optional.of(d.acptNo());
            }
        }
        return Optional.empty();
    }

    /** @throws IllegalStateException 응답에 공시 테이블이 없으면 (차단·점검 페이지 의심) */
    static List<KindDisclosure> parse(String html) {
        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table.list");
        if (table == null) {
            throw new IllegalStateException("KIND 응답에 공시 테이블이 없습니다 — 차단 또는 페이지 구조 변경 의심");
        }

        List<KindDisclosure> result = new ArrayList<>();
        for (Element row : table.select("tbody tr")) {
            List<Element> tds = row.select("> td");
            if (tds.size() < 4) continue;

            Element companyLink = tds.get(1).selectFirst("a");
            Element titleLink = tds.get(2).selectFirst("a");
            if (companyLink == null || titleLink == null) continue;

            Matcher m = ACPT_NO_PATTERN.matcher(titleLink.attr("onclick"));
            if (!m.find()) continue;

            Element marketIcon = tds.get(1).selectFirst("img");
            // 목록 화면은 긴 제목을 말줄임하므로 전체 제목이 담긴 title 속성을 우선 사용
            String title = titleLink.hasAttr("title") ? titleLink.attr("title") : titleLink.text();
            // KIND는 정정 표시를 title 속성이 아니라 링크 텍스트의 <font>[정정]</font>로만 넣는다.
            // title 속성만 쓰면 정정 여부가 사라지므로(DART의 "[기재정정]"과 달리), 링크 텍스트에서
            // 정정을 감지해 접두어로 복원한다 — 이래야 알림이 "[정정]"으로 표기된다.
            if (!title.contains("정정") && titleLink.text().contains("정정")) {
                title = "[정정]" + title;
            }

            result.add(new KindDisclosure(
                    tds.get(0).text().trim(),
                    marketIcon != null ? marketIcon.attr("alt").trim() : "",
                    companyLink.text().trim(),
                    title.trim(),
                    m.group(1),
                    tds.get(3).text().trim()
            ));
        }
        return result;
    }
}
