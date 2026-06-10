package com.example.dart.filter;

import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2단계 호재 필터.
 *
 * Stage 1 (isGoodNewsTitle): DART 표준 공시명 기준 — 제목만으로 빠르게 후보 선별.
 * Stage 2 (isGoodNewsBody) : 원문 본문 기준 — 미확정 공시 제외, 계약금액 비율 검증.
 */
public class NewsFilter {

    // ── Stage 1 : 공시 제목 필터 ─────────────────────────────────────────

    /**
     * 호재처럼 보이지만 취소·보정 공시.
     * 하나라도 포함되면 즉시 제외.
     */
    private static final List<String> EXCLUDE_TITLE = List.of(
            "기재정정", "정정",   // 기존 공시 수정본 — 중복
            "해지"               // 계약 해지 = 악재
    );

    /**
     * DART 표준 공시 양식명 기반 호재 키워드.
     * report_nm은 "단일판매ㆍ공급계약체결결정", "자기주식취득결정" 같은 정형화된 문자열.
     */
    private static final List<String> GOOD_TITLE = List.of(
            // 수주·계약 (단일판매ㆍ공급계약체결결정)
            "단일판매",
            "수주",
            // 주주환원
            "자기주식취득결정",
            "자기주식취득신탁",     // 자기주식취득신탁계약체결
            "무상증자결정",
            "현금배당결정",
            "현물배당결정",
            "주식배당결정",
            "액면분할결정",
            // M&A·투자
            "합병결정",
            "영업양수도",
            "타법인주식및출자증권취득결정",
            "유형자산양수도",
            "신규시설투자",
            // 지식재산
            "특허권취득",
            // 바이오·신약
            "임상시험계획승인",
            "품목허가",
            "기술이전계약",
            "기술수출계약"
    );

    // ── Stage 2 : 본문 텍스트 필터 ───────────────────────────────────────

    /** 확정되지 않은 공시 식별자 — 포함 시 제외. */
    private static final List<String> UNCERTAIN_BODY = List.of(
            "조건부"
    );

    /**
     * 매출액 대비 계약금액 비율 최소 기준 (%).
     * 공급계약 공시에 비율이 명시된 경우에만 적용.
     * 미만이면 주가 영향이 미미하다고 판단해 제외.
     */
    private static final double MIN_SALES_RATIO = 10.0;

    // "매출액 대비(%) 15.5" / "최근매출액대비 10.2%" 등 다양한 표현 포괄
    private static final Pattern RATIO_PATTERN = Pattern.compile(
            "매출액[^0-9]{0,30}([0-9]+(?:\\.[0-9]+)?)"
    );

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Stage 1: 공시 제목만으로 호재 후보 여부 판단.
     * 본문 조회(네트워크) 없이 빠르게 필터링.
     */
    public boolean isGoodNewsTitle(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) return false;

        for (String ex : EXCLUDE_TITLE) {
            if (reportNm.contains(ex)) return false;
        }

        for (String kw : GOOD_TITLE) {
            if (reportNm.contains(kw)) return true;
        }

        return false;
    }

    /**
     * Stage 2: 원문 본문으로 확정 여부·규모 검증.
     * Stage 1 통과 후 호출. 본문이 없으면 통과 처리.
     *
     * 제외 조건:
     *  ① 본문에 "조건부" 포함 → 미확정
     *  ② 계약금액/매출액 비율이 명시됐으나 10% 미만 → 규모 미미
     */
    public boolean isGoodNewsBody(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) return true;

        // ① 미확정 공시 제외
        for (String uncertain : UNCERTAIN_BODY) {
            if (bodyText.contains(uncertain)) return false;
        }

        // ② 매출액 대비 비율이 있으면 규모 검증
        OptionalDouble ratio = extractSalesRatio(bodyText);
        if (ratio.isPresent() && ratio.getAsDouble() < MIN_SALES_RATIO) {
            return false;
        }

        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static OptionalDouble extractSalesRatio(String bodyText) {
        Matcher m = RATIO_PATTERN.matcher(bodyText);
        if (m.find()) {
            try {
                return OptionalDouble.of(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return OptionalDouble.empty();
    }
}
