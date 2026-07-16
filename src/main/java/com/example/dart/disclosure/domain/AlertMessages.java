package com.example.dart.disclosure.domain;

import com.example.dart.common.domain.KoreanMoney;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * DART 공시 알림 문자열의 순수 조립 — 네트워크·로깅 없음, 모든 입력은 파라미터로 받는다.
 * 조회·재시도·신호 발화 같은 오케스트레이션은 application의 DisclosureEnricher가 담당한다.
 */
public final class AlertMessages {

    /** DART 목록 API는 접수 "일"만 주므로, 시각은 감지 시점(폴링 7초 주기 ≈ 게시 시각)으로 보여준다. */
    private static final DateTimeFormatter DETECT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private AlertMessages() {}

    /** 1단계 — 감지 즉시 전송할 헤더. 본문 조회 없음(가장 빠름). */
    public static String header(Disclosure d, NewsFilter.TitleMatch match, ZonedDateTime detectedAt) {
        boolean correction = NewsFilter.isCorrection(d.reportNm());
        return String.format(
                "%s **%s%s · %s** | %s — %s\n접수 %s · 감지 %s · 제출인 %s\n%s",
                correction ? "🔁" : "📋", correction ? "[정정] " : "",
                match.category(), d.marketName(), d.corpName(), d.reportNm(),
                formatDate(d.rceptDt()), DETECT_TIME_FMT.format(detectedAt),
                d.flrNm(), dartUrl(d.rceptNo()));
    }

    /**
     * 2단계 후속 메시지 본문 — DART 원문·KIND 본문 두 경로가 공유한다.
     *   📊 시총·매출 대비 | 회사 — 공시명 / 💰 계약금액·매출 대비·시총 대비 / 📈 시총·매출액·계약상대방·계약기간.
     * 링크는 넣지 않는다(원문은 헤더 알림의 링크로 확인).
     */
    public static String followup(Disclosure d, ContractInfo c, OptionalLong cap, OptionalLong revenue) {
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출 대비** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));

        if (c.contractWon().isPresent()) {
            long won = c.contractWon().getAsLong();
            sb.append("\n💰 계약금액 ").append(KoreanMoney.format(won));

            // 매출 대비 % — 공시 명시값(거래소 표준) 우선, 없으면 계약금액÷매출액. 연환산은 안 함(공시값과 일치).
            String salesLabel = ContractInfo.salesRatioLabel(won, revenue, c.salesRatioPct());
            if (salesLabel != null) {
                sb.append(" · ").append(salesLabel);
            }

            // 시총 대비 % — 종목코드로 조회한 시가총액 대비(딜 규모 vs 회사 가치라 총액 기준).
            if (cap.isPresent() && cap.getAsLong() > 0) {
                sb.append(String.format(" · 시총 대비 %.1f%%", won * 100.0 / cap.getAsLong()));
            }
        }

        // 📈 핵심정보 한 줄 — 시총·매출 원시값 + 계약 부가정보(있는 것만).
        List<String> info = new ArrayList<>();
        cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
        revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (c.counterparty() != null) info.add("계약상대방 " + c.counterparty());
        if (c.period() != null) info.add("계약기간 " + c.period());
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));

        return sb.toString();
    }

    /** 규모 분석 실패 시 후속 폴백 한 줄 — 헤더는 이미 나갔으므로 실패 사실만 짧게. */
    public static String followupFailure(Disclosure d) {
        return String.format("📊 **%s시총·매출 대비** | %s — %s\n(상세 내역 조회 실패)",
                NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm());
    }

    /**
     * 시총·매출 메시지 본문 — 규모 전용·자기주식 취득/신탁/소각이 공유한다.
     * amount가 있으면(취득금액/신탁계약금액/소각예정금액) amountLabel과 금액, 시총 대비 매입 강도(%)를 함께 표시한다.
     */
    public static String scale(Disclosure d, OptionalLong cap, OptionalLong revenue,
                               OptionalLong amount, String amountLabel) {
        StringBuilder sb = new StringBuilder(
                String.format("📊 **%s시총·매출** | %s — %s",
                        NewsFilter.isCorrection(d.reportNm()) ? "[정정] " : "", d.corpName(), d.reportNm()));
        if (amount.isPresent()) {
            long won = amount.getAsLong();
            sb.append("\n💰 ").append(amountLabel).append(" ").append(KoreanMoney.format(won));
            if (cap.isPresent() && cap.getAsLong() > 0) {
                sb.append(String.format(" · 시총 대비 %.1f%%", won * 100.0 / cap.getAsLong()));
            }
        }
        List<String> info = new ArrayList<>();
        cap.ifPresent(v -> info.add("시총 " + KoreanMoney.format(v)));
        revenue.ifPresent(rev -> info.add("매출액 " + KoreanMoney.format(rev)));
        if (!info.isEmpty()) sb.append("\n📈 ").append(String.join(" · ", info));
        return sb.toString();
    }

    private static String dartUrl(String rceptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;
    }

    /** "20260610" → "2026-06-10" */
    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
