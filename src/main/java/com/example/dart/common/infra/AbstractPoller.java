package com.example.dart.common.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 단일 {@code poll()} 루프를 가진 폴러의 공용 생명주기 — 이름 있는 스케줄러 생성, 시작 로그,
 * 고정 지연(초) 반복 예약, graceful stop 골격을 한곳으로 모은다. 폴링 본문(poll)만 하위가 구현한다.
 *
 * <p>여러 주기의 태스크를 각각 스케줄하는 폴러({@code NewsPollerService}·{@code KisPollerService})는
 * 이 단일 poll 모델과 맞지 않아 <b>일부러 상속하지 않는다</b> — 그쪽은 자체 start/stop을 유지한다.
 * (억지로 다중 스케줄을 훅으로 끼워 넣지 않는다 — ARCHITECTURE §9 과잉 설계 금지.)
 *
 * <p>{@code log}는 {@code getClass()} 기준이라 로그 이름이 실제 하위 클래스로 찍힌다({@code HttpNotifier}와 동일 관용).
 */
public abstract class AbstractPoller {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final PollWorker scheduler;
    private final int intervalSec;
    private final String startMessage;

    /**
     * @param threadName   스케줄러 스레드 이름(예: "dart-poller")
     * @param intervalSec  poll() 반복 주기(초)
     * @param startMessage start() 시 남길 시작 로그(플레이스홀더 없이 완성된 문장)
     */
    protected AbstractPoller(String threadName, int intervalSec, String startMessage) {
        this.scheduler = new PollWorker(threadName);
        this.intervalSec = intervalSec;
        this.startMessage = startMessage;
    }

    /** 시작 로그를 남기고 poll()을 즉시 1회 + intervalSec 간격으로 반복 예약한다. */
    public final void start() {
        log.info(startMessage);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec);
    }

    /** 폴링 1회. 예외 처리(백오프·로그)는 하위 구현이 자체 책임진다 — 폴러마다 실패 정책이 다르다. */
    protected abstract void poll();

    /** 스케줄러를 멈추고 하위의 추가 자원 정리({@link #onStop()})를 수행한다. */
    public void stop() {
        scheduler.stop();
        onStop();
    }

    /** 하위가 보강 스레드 풀 등 추가 자원을 정리하고 종료 로그를 남길 훅. 기본 무동작. */
    protected void onStop() {}
}
