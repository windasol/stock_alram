package com.example.dart.common.infra;

import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/**
 * 보강(enrichment) 재시도 공용 골격 — "제출 → 재시도 대상 예외면 일정 간격 뒤 attempt+1 재제출 →
 * 최대 횟수 소진 시 폴백"의 동형 구조(DART 규모/취득금액·KIND 계약/소각 보강)를 통합한다.
 * 로그 문구·폴백 동작은 콜사이트마다 달라 콜백으로 받는다.
 */
public final class RetryScheduler {

    /** 재시도 대상 작업 — 예외를 던지면 retryable 판정에 따라 재시도/중단이 갈린다. */
    @FunctionalInterface
    public interface Attempt {
        void run() throws Exception;
    }

    private final PollWorker pool;
    private final long retryDelaySec;
    private final int maxAttempts;

    public RetryScheduler(PollWorker pool, long retryDelaySec, int maxAttempts) {
        this.pool = pool;
        this.retryDelaySec = retryDelaySec;
        this.maxAttempts = maxAttempts;
    }

    public long retryDelaySec() {
        return retryDelaySec;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 모든 예외를 재시도 대상으로 보는 간편형 — {@code onAbort}는 도달 불가라 생략. */
    public void run(Attempt action, ObjIntConsumer<Exception> onRetry, Consumer<Exception> onExhausted) {
        run(action, e -> true, onRetry, onExhausted, e -> {});
    }

    /**
     * action을 즉시 제출한다. 실패 시:
     *  - retryable이 true인 예외 → onRetry(e, attempt) 후 retryDelaySec 뒤 재시도, maxAttempts 소진 시 onExhausted(e)
     *  - retryable이 false인 예외 → 즉시 onAbort(e) (재시도 없음)
     */
    public void run(Attempt action, Predicate<Exception> retryable, ObjIntConsumer<Exception> onRetry,
                    Consumer<Exception> onExhausted, Consumer<Exception> onAbort) {
        runAttempt(1, action, retryable, onRetry, onExhausted, onAbort);
    }

    private void runAttempt(int attempt, Attempt action, Predicate<Exception> retryable,
                            ObjIntConsumer<Exception> onRetry, Consumer<Exception> onExhausted,
                            Consumer<Exception> onAbort) {
        pool.submit(() -> {
            try {
                action.run();
            } catch (Exception e) {
                if (!retryable.test(e)) {
                    onAbort.accept(e);
                } else if (attempt < maxAttempts) {
                    onRetry.accept(e, attempt);
                    pool.schedule(() -> runAttempt(attempt + 1, action, retryable, onRetry, onExhausted, onAbort),
                            retryDelaySec);
                } else {
                    onExhausted.accept(e);
                }
            }
        });
    }
}
