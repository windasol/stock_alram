package com.example.dart.common.domain;

import java.time.ZoneId;

/**
 * 한국 표준시(KST) 존 — 프로젝트 전역 공용 상수.
 *
 * <p>이 봇의 모든 시각 판정(장 세션·폴링 시각·공시 접수일·수급 동결 시각 등)은 KST 기준이다.
 * {@code ZoneId.of("Asia/Seoul")}가 13개 파일에 각자 재선언돼 있었는데, 존 문자열 오타 하나가
 * 조용한 시각 버그가 되므로 한곳으로 모은다({@link com.example.dart.common.domain} = 순수 도메인, IO 없음).
 */
public final class KstTime {

    /** Asia/Seoul (KST, UTC+9, 서머타임 없음). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KstTime() {}
}
