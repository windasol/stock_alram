package com.example.dart.kis.domain;

import java.time.LocalTime;

/**
 * 수급 발송 단계 — 시각에 따라 가집계 추정 폴링 / KRX 확정 1회 / NXT 최종 확정 1회로 갈린다.
 *
 * 전환 시각(KST): 가집계(추정)는 09:30~14:30 입력으로 14:30이 마지막이라(KIS 공식),
 * 정규장 확정 집계 시각(KRX 15:35) 이후엔 가집계 대신 '확정치'(inquire-investor)로 그 날 1회 발송한다.
 *  - 09:00~15:35 : 가집계 추정 10분 폴링(표 + 분석)
 *  - 15:35~20:05 : KRX 확정 수급 1회(이후 NXT 라이브 수급은 데이터 소스가 없어 폴링하지 않음)
 *  - 20:05~      : NXT 최종 확정 집계 시각 — 최종 확정 1회, 이후 중단
 */
public enum FlowPhase {
    ESTIMATE, KRX_CONFIRMED, NXT_CONFIRMED;

    public static final LocalTime KRX_CONFIRMED_AFTER = LocalTime.of(15, 35);
    public static final LocalTime NXT_CONFIRMED_AFTER = LocalTime.of(20, 5);

    /** 시각이 속한 수급 단계. 09:00 이전이면 null(발송 없음). */
    public static FlowPhase at(LocalTime t) {
        if (!t.isBefore(NXT_CONFIRMED_AFTER)) return NXT_CONFIRMED;   // ≥ 20:05
        if (!t.isBefore(KRX_CONFIRMED_AFTER)) return KRX_CONFIRMED;   // ≥ 15:35
        if (!t.isBefore(Session.REGULAR_OPEN)) return ESTIMATE;       // ≥ 09:00
        return null;
    }

    /**
     * 확정 수급 조회용 시장구분 — 현재 시각으로 판단한다. NXT 최종 집계 시각(20:05) 이후면 NXT("NX"),
     * 그 전(정규장 확정 구간)이면 KRX("J"). 호출부가 넘기는 세션이 아니라 실제 시계로 결정한다.
     */
    public static String confirmedMarketDiv(LocalTime t) {
        return !t.isBefore(NXT_CONFIRMED_AFTER) ? "NX" : "J";
    }
}
