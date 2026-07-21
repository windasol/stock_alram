package com.example.dart.common.domain;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 한국 표준시(KST) 존 + 시각 표시 포맷 — 프로젝트 전역 공용 상수.
 *
 * <p>이 봇의 모든 시각 판정(장 세션·폴링 시각·공시 접수일·수급 동결 시각 등)은 KST 기준이다.
 * {@code ZoneId.of("Asia/Seoul")}가 13개 파일에 각자 재선언돼 있었는데, 존 문자열 오타 하나가
 * 조용한 시각 버그가 되므로 한곳으로 모은다({@link com.example.dart.common.domain} = 순수 도메인, IO 없음).
 *
 * <p>알림 메시지의 "HH:mm" 시각 표시도 여러 컨텍스트(kis·trade·pricetrack)에 같은 포맷터가 이름만 달리
 * 중복돼 있어 함께 모은다({@link #HH_MM}). {@link DateTimeFormatter}는 불변·스레드 안전이라 공유해도 된다.
 */
public final class KstTime {

    /** Asia/Seoul (KST, UTC+9, 서머타임 없음). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 알림용 시:분 표시 포맷(예: "14:30"). 불변·스레드 안전 — 전역 공유. */
    public static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private KstTime() {}
}
