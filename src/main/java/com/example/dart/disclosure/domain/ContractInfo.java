package com.example.dart.disclosure.domain;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 수주공급계약 공시의 핵심값 — 알림·자동매매 트리거가 공유하는 도메인 record. 없는 항목은 비어있음/null.
 * 값 추출(본문 파싱)은 infra의 DocumentParser가 하고, 비율 판정 로직은 여기(도메인)에 둔다.
 */
public record ContractInfo(
        OptionalLong contractWon,
        Double salesRatioPct,
        OptionalLong recentRevenueWon,
        String counterparty,
        String period) {

    /**
     * 매출 대비 표시 문자열. 공시 명시 비율(statedPct = 거래소 표준 지표: 계약총액 ÷ 최근 연매출)을 그대로 쓰고,
     * 없으면 계약금액 ÷ 매출액으로 계산한다.
     *
     * 연환산(÷계약연수)은 하지 않는다 — 공시·시장이 쓰는 공식 수치를 변형하면 공시에 적힌 값과 어긋나
     * "틀린 값"처럼 보이고, 계약기간 시작이 과거인 정정·장기계약에선 경과분까지 나뉘어 더 왜곡된다.
     * 다년 여부는 계약기간을 별도(📈 줄)로 표시해 확인한다.
     *
     * @return 비율을 못 구하면 null
     */
    public static String salesRatioLabel(long contractWon, OptionalLong revenue, Double statedPct) {
        OptionalDouble pct = salesRatioValue(contractWon, revenue, statedPct);
        return pct.isPresent() ? String.format("매출 대비 %.1f%%", pct.getAsDouble()) : null;
    }

    /**
     * 매출 대비 비율의 숫자값(%). 공시 명시 비율(statedPct)을 우선하고, 없으면 계약금액 ÷ 매출액으로 계산한다.
     * 자동매매 트리거(≥N%) 판정과 {@link #salesRatioLabel} 표시가 같은 값을 쓰도록 로직을 단일화한다.
     *
     * @return 비율을 못 구하면 비어있음(OptionalDouble.empty)
     */
    public static OptionalDouble salesRatioValue(long contractWon, OptionalLong revenue, Double statedPct) {
        if (statedPct != null) return OptionalDouble.of(statedPct);
        if (revenue.isPresent() && revenue.getAsLong() > 0) {
            return OptionalDouble.of(contractWon * 100.0 / revenue.getAsLong());
        }
        return OptionalDouble.empty();
    }
}
