package com.example.dart.common.infra;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 폴러 공용 스케줄러 래퍼 — 이름 있는 스레드 풀 생성과 graceful shutdown(5초 대기 후 강제 종료)
 * 보일러플레이트를 한 곳으로 모은다. 폴러·추적기·자동매매가 executor를 직접 만들지 않고 이걸 쓴다.
 * 시간 단위는 전 콜사이트가 초 단위라 초로 고정한다.
 */
public final class PollWorker {

    private static final long STOP_WAIT_SEC = 5;

    private final ScheduledExecutorService pool;

    public PollWorker(String threadName) {
        this(threadName, 1);
    }

    public PollWorker(String threadName, int threads) {
        this.pool = Executors.newScheduledThreadPool(threads, r -> new Thread(r, threadName));
    }

    public void scheduleWithFixedDelay(Runnable task, long initialDelaySec, long periodSec) {
        pool.scheduleWithFixedDelay(task, initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    public void schedule(Runnable task, long delaySec) {
        pool.schedule(task, delaySec, TimeUnit.SECONDS);
    }

    public void submit(Runnable task) {
        pool.submit(task);
    }

    /** shutdown 후 {@value STOP_WAIT_SEC}초 안에 안 끝나면 강제 종료. 인터럽트 시에도 강제 종료 후 플래그 복원. */
    public void stop() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(STOP_WAIT_SEC, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
