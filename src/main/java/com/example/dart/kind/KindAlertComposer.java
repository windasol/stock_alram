package com.example.dart.kind;

import com.example.dart.filter.NewsFilter;
import com.example.dart.parse.DocumentParser;
import com.example.dart.util.KoreanMoney;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * KIND 호재 공시 1건을 알림 메시지로 조립한다.
 *
 * 2단계: 감지 즉시 헤더(compose)를 보내고, KIND 뷰어 본문에서 뽑은 규모를 후속(composeFollowup)으로 잇는다.
 * KIND 본문은 DART {@code document.xml}보다 먼저 공개되므로, 계약금액·매출 대비·시총 대비를 헤더와 거의
 * 동시에(수초) 보여줄 수 있다 — DART 원문 지연(status 014)에 묶이지 않는다.
 */
public class KindAlertComposer {

    private static final DateTimeFormatter DETECT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 1단계 — 감지 즉시 전송할 헤더. 본문 조회 없음(가장 빠름). */
    public String compose(KindDisclosure d, NewsFilter.TitleMatch match) {
        return String.format(
                "⚡ **%s · %s** | %s — %s\n공시 %s · 감지 %s · 제출인 %s · KIND 선행\n%s",
                match.category(), d.market(), d.company(), d.title(),
                d.time(), DETECT_TIME_FMT.format(ZonedDateTime.now(KST)),
                d.submitter(), d.detailUrl());
    }

    /**
     * 2단계 — KIND 뷰어 본문에서 뽑은 규모를 담은 후속. DART {@code AlertComposer.composeFollowup}과 동일 포맷:
     *   📊 시총·매출 대비 | 회사 — 공시명 / 💰 계약금액·매출 대비·시총 대비 / 📈 매출액·계약상대방·계약기간.
     * 링크는 넣지 않는다(원문은 헤더 알림의 링크로 확인).
     *
     * @param c         KIND 본문에서 파싱한 핵심값 (계약금액·매출액대비·최근매출액·계약상대방·계약기간)
     * @param marketCap 종목 시가총액(원) — 종목코드 없거나 조회 실패면 empty
     */
    public String composeFollowup(KindDisclosure d, DocumentParser.ContractInfo c, OptionalLong marketCap) {
        StringBuilder sb = new StringBuilder(
                String.format("📊 **시총·매출 대비** | %s — %s", d.company(), d.title()));

        if (c.contractWon().isPresent()) {
            long won = c.contractWon().getAsLong();
            sb.append("\n💰 계약금액 ").append(KoreanMoney.format(won));

            // 매출 대비 % — 공시 명시값 우선, 없으면 계약금액÷매출액.
            Double pct = c.salesRatioPct();
            if (pct == null && c.recentRevenueWon().isPresent() && c.recentRevenueWon().getAsLong() > 0) {
                pct = won * 100.0 / c.recentRevenueWon().getAsLong();
            }
            if (pct != null) {
                sb.append(String.format(" · 매출 대비 %.1f%%", pct));
            }

            // 시총 대비 % — 뷰어에서 읽은 종목코드로 조회한 시가총액 대비.
            if (marketCap.isPresent() && marketCap.getAsLong() > 0) {
                sb.append(String.format(" · 시총 대비 %.1f%%", won * 100.0 / marketCap.getAsLong()));
            }
        }

        // 📈 핵심정보 한 줄 — 있는 항목만.
        List<String> info = new ArrayList<>();
        c.recentRevenueWon().ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (c.counterparty() != null) info.add("계약상대방 " + c.counterparty());
        if (c.period() != null) info.add("계약기간 " + c.period());
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));

        return sb.toString();
    }
}
