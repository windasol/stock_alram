package com.example.dart.common.domain;

import java.time.LocalTime;

/**
 * 한국 주식시장 세션 시각 판정 — 공유 커널(순수 함수, 시각은 파라미터로 받는다).
 *
 * KRX 정규장(09:00~15:30) 밖의 프리마켓(08:00~09:00)·애프터마켓(15:30~20:00)은 NXT만 거래하므로,
 * KIS 분봉 조회 시장구분을 통합("UN")으로 바꿔야 하는지를 이 판정으로 가른다.
 * 주가추적·자동매매 등 여러 컨텍스트가 같은 경계를 쓰므로 여기 한 곳에서만 정의한다.
 * (KIS 등락률순위의 J/NX 전환 경계(15:40)는 KIS 전용 개념이라 여기 두지 않는다 — kis 컨텍스트 참조.)
 */
public final class TradingSession {

    /** KRX 정규장. */
    public static final LocalTime KRX_OPEN = LocalTime.of(9, 0);
    public static final LocalTime KRX_CLOSE = LocalTime.of(15, 30);
    /** NXT 연장 거래(프리마켓 시작 ~ 애프터마켓 종료) — 주가추적·자동매매 감시 운영시간의 상·하한. */
    public static final LocalTime EXTENDED_OPEN = LocalTime.of(8, 0);
    public static final LocalTime EXTENDED_CLOSE = LocalTime.of(20, 0);

    private TradingSession() {}

    /** KRX 정규장(09:00~15:30) 밖이면 true — 프리·애프터마켓은 NXT만 거래하므로 통합("UN") 분봉이 필요. */
    public static boolean nxtSession(LocalTime t) {
        return t.isBefore(KRX_OPEN) || !t.isBefore(KRX_CLOSE);
    }

    /** NXT 연장 포함 거래시간(08:00~20:00, 경계 포함) 안이면 true — 주가 추적·자동매매 감시 운영 창. */
    public static boolean withinExtendedHours(LocalTime t) {
        return !t.isBefore(EXTENDED_OPEN) && !t.isAfter(EXTENDED_CLOSE);
    }
}
