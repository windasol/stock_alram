package com.example.dart.common.infra;

/**
 * 폴링 연속 실패 지수 백오프 — 실패가 이어지면 다음 폴링을 2^n회(상한 {@value #MAX_SKIP}) 건너뛴다.
 * 외부 소스 차단·점검 중 무의미한 재시도를 억제한다. 단일 폴러 스레드 접근 전제(동기화 없음).
 */
public final class PollBackoff {

    private static final int MAX_SKIP = 40;

    private int consecutiveFailures = 0;
    private int skipPolls = 0;

    /** 이번 폴링을 건너뛰어야 하면 true(남은 스킵 1회 차감). */
    public boolean shouldSkip() {
        if (skipPolls > 0) {
            skipPolls--;
            return true;
        }
        return false;
    }

    public void success() {
        consecutiveFailures = 0;
    }

    /** 실패 1회 기록 — 건너뛸 폴링 횟수(지수, 상한 {@value #MAX_SKIP})를 계산해 반환한다(로깅용). */
    public int failure() {
        consecutiveFailures++;
        skipPolls = Math.min(1 << consecutiveFailures, MAX_SKIP);
        return skipPolls;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }
}
