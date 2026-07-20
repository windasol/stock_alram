package com.example.dart.econcal.application;

import com.example.dart.common.infra.PollWorker;
import com.example.dart.config.AppConfig;
import com.example.dart.econcal.domain.EconCalendarComposer;
import com.example.dart.econcal.domain.EconEvent;
import com.example.dart.econcal.domain.FomcSchedule;
import com.example.dart.econcal.infra.FinnhubClient;
import com.example.dart.econcal.infra.FredClient;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 매일 아침 정해진 시각에 "향후 N일 주요 일정"(경제지표·기업 실적·FOMC)을 한 번 발송하는 폴러.
 *
 * <p>{@link com.example.dart.common.infra.AbstractPoller}를 <b>상속하지 않는다</b> — AbstractPoller는
 * 시작 즉시 1회 + 고정 간격 반복인데, 이 기능은 <b>특정 시각(예: 08:00)에 하루 1회</b>라 벽시계 정렬이 필요하다
 * ({@code KisPollerService}가 섹터 요약을 정렬하는 것과 같은 이유). 다음 발송 시각까지의 지연을 계산해
 * 그 시각에 첫 발송하고 이후 24시간 주기로 반복하므로, 재시작해도 오늘 발송분이 지났으면 다음은 내일이라
 * <b>이중 발송이 없다</b>(별도 dedup 상태파일 불필요).
 */
public class EconCalendarPoller {

    private static final Logger log = LoggerFactory.getLogger(EconCalendarPoller.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long DAY_SEC = 24 * 60 * 60L;

    /** 미국 지표 소스. null이면 FRED 키 미설정 — 지표 없이 실적·FOMC만. */
    private final FredClient fred;
    /** 기업 실적 소스. null이면 Finnhub 키 미설정 — 실적 없이 지표·FOMC만. */
    private final FinnhubClient finnhub;
    private final Notifier notifier;
    private final LocalTime sendTime;
    private final int windowDays;
    private final List<String> tickers;
    private final List<String> releaseKeywords;

    private final PollWorker scheduler = new PollWorker("econcal-poller");

    public EconCalendarPoller(FredClient fred, FinnhubClient finnhub, Notifier notifier,
                              AppConfig.EconCalendarConfig config) {
        this.fred = fred;
        this.finnhub = finnhub;
        this.notifier = notifier;
        this.sendTime = config.sendTimeParsed();
        this.windowDays = config.windowDays();
        this.tickers = config.tickers();
        this.releaseKeywords = config.releaseKeywords();
    }

    public void start() {
        long initialDelaySec = secondsUntil(sendTime, ZonedDateTime.now(KST));
        scheduler.scheduleWithFixedDelay(this::sendDigest, initialDelaySec, DAY_SEC);
        log.info("📅 경제/실적 캘린더 다이제스트 활성 (매일 {} 발송, 향후 {}일, 첫 발송 {}분 후, 지표 {} · 실적 {})",
                sendTime, windowDays, initialDelaySec / 60,
                fred != null ? "ON" : "OFF", finnhub != null ? "ON" : "OFF");
    }

    public void stop() {
        scheduler.stop();
        log.info("경제/실적 캘린더 다이제스트 중지 완료");
    }

    /** 향후 N일 이벤트를 모아 한 메시지로 발송한다. 각 소스 실패는 격리(빈 목록)돼 나머지로 계속된다. */
    private void sendDigest() {
        try {
            LocalDate from = LocalDate.now(KST);
            LocalDate to = from.plusDays(windowDays);
            List<EconEvent> events = new ArrayList<>();
            if (fred != null) events.addAll(fred.upcoming(from, to, releaseKeywords));
            if (finnhub != null) events.addAll(finnhub.upcoming(from, to, tickers));
            events.addAll(FomcSchedule.within(from, to));
            notifier.send(EconCalendarComposer.compose(events, from, to));
            log.info("경제/실적 캘린더 다이제스트 발송 ({}건)", events.size());
        } catch (Exception e) {
            log.warn("경제/실적 캘린더 다이제스트 발송 실패: {}", e.toString());
        }
    }

    /** {@code now} 기준 다음 {@code target} 시각까지의 초. 오늘 시각이 이미 지났으면 내일로. (테스트용 static) */
    static long secondsUntil(LocalTime target, ZonedDateTime now) {
        ZonedDateTime todayTarget = now.toLocalDate().atTime(target).atZone(now.getZone());
        ZonedDateTime next = now.isBefore(todayTarget) ? todayTarget : todayTarget.plusDays(1);
        return Duration.between(now, next).getSeconds();
    }
}
