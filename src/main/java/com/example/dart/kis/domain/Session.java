package com.example.dart.kis.domain;

import java.time.LocalTime;

/**
 * KIS 등락률순위 폴링의 시장 세션 — 조회 시장구분(J/NX)과 알림 표시 라벨을 함께 든다.
 *
 * 폴링 운영시간 — 현재 시각(KST)에 맞춰 시장구분을 자동 전환한다.
 *  - 정규장(KRX, 시장구분 J): 09:00~15:40 (마감 동시호가까지 KRX).
 *  - NXT 애프터마켓(NXT, 시장구분 NX): 15:40~20:00.
 * 이 시간 밖(야간·주말)엔 폴링하지 않는다. 두 구간은 15:40에서 맞닿아 폴링 공백이 없다.
 * (KRX 정규장 자체의 경계(15:30)는 common의 TradingSession — 여기 15:40은 KIS 순위 API 전환점.)
 */
public enum Session {
    REGULAR("J", "정규장"),
    NXT_AFTER("NX", "🌆 NXT 애프터마켓");

    public static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    public static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 40);
    public static final LocalTime NXT_AFTER_CLOSE = LocalTime.of(20, 0);

    public final String marketDiv;
    public final String label;

    Session(String marketDiv, String label) {
        this.marketDiv = marketDiv;
        this.label = label;
    }

    /** 시각이 속한 세션 — 운영시간 밖이면 null. 09:00~15:40 정규장(J), 15:40~20:00 NXT 애프터마켓(NX). */
    public static Session at(LocalTime t) {
        if (!t.isBefore(REGULAR_OPEN) && t.isBefore(REGULAR_CLOSE)) return REGULAR;
        if (!t.isBefore(REGULAR_CLOSE) && !t.isAfter(NXT_AFTER_CLOSE)) return NXT_AFTER;
        return null;
    }
}
