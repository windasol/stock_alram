package com.example.dart.kind;

import com.example.dart.config.AppConfig;
import com.example.dart.filter.NewsFilter;
import com.example.dart.notify.Notifier;
import com.example.dart.util.DisclosureKeys;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * KIND 오늘의공시 폴러 — DART 폴러(PollerService)와 독립된 스레드에서 병렬로 동작한다.
 * 같은 공시가 양쪽에 게시되므로 공유 교차 중복 키 저장소(DisclosureKeys)로 먼저 잡은 쪽만 알린다.
 *
 * 흐름: 신규(접수번호 기준) → 시장 필터(CORP_CLS) → 제목 필터(NewsFilter 재사용)
 *      → 나이 제한 → 교차 중복 → 알림.
 * 본문(Stage 2) 필터는 생략 — KIND 뷰어 원문 파싱은 별도 작업이고, 수주공급계약의
 * 소액 계약 오탐을 약간 감수하는 대신 속도를 얻는다 (재현율 우선).
 */
public class KindPollerService {

    private static final Logger log = LoggerFactory.getLogger(KindPollerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** KIND 공시 게시 시간대 밖에는 폴링하지 않는다 — 야간 무의미 트래픽 방지 (DART 폴러는 24시간 커버). */
    private static final LocalTime OPEN = LocalTime.of(7, 0);
    private static final LocalTime CLOSE = LocalTime.of(20, 0);

    /**
     * 게시된 지 이만큼 지난 공시는 기록만 하고 알리지 않는다 —
     * 첫 실행·재시작 시 당일 누적 공시(DART가 이미 알린 것 포함)가 한꺼번에 쏟아지는 것을 막는다.
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(30);

    private final KindClient client;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final KindAlertComposer alertComposer;
    private final SeenStore seenStore;
    private final SeenStore disclosureKeys;
    private final Set<String> allowedMarkets;
    private final int intervalSec;
    private final ScheduledExecutorService scheduler;

    private int consecutiveFailures = 0;
    private int skipPolls = 0;

    public KindPollerService(KindClient client, NewsFilter newsFilter, Notifier notifier,
                             KindAlertComposer alertComposer, SeenStore seenStore,
                             SeenStore disclosureKeys, AppConfig config) {
        this.client = client;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.seenStore = seenStore;
        this.disclosureKeys = disclosureKeys;
        // DART와 같은 CORP_CLS 설정 공유 — 빈 값이면 전체 시장
        this.allowedMarkets = config.corpCls() == null || config.corpCls().isBlank()
                ? Set.of()
                : Arrays.stream(config.corpCls().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.intervalSec = config.kindPollIntervalSec();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kind-poller"));
    }

    public void start() {
        log.info("KIND 폴링 시작 (주기: {}초, 운영시간 {}~{} KST)", intervalSec, OPEN, CLOSE);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("KIND 폴링 중지 완료");
    }

    private void poll() {
        if (skipPolls > 0) {
            skipPolls--;
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(KST);
        LocalTime t = now.toLocalTime();
        if (t.isBefore(OPEN) || t.isAfter(CLOSE)) return;

        List<KindDisclosure> disclosures;
        try {
            disclosures = client.fetchToday();
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            // 연속 실패 시 폴링을 건너뛰며 점진 백오프 — 차단·점검 중 무의미한 재시도 억제
            skipPolls = Math.min(1 << consecutiveFailures, 40);
            log.warn("KIND 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    consecutiveFailures, skipPolls, e.toString());
            return;
        }

        for (KindDisclosure d : disclosures) {
            if (!allowedMarkets.isEmpty() && !allowedMarkets.contains(d.marketCode())) continue;
            if (!seenStore.add(d.acptNo())) continue;
            handle(d, now);
        }
    }

    /** 신규 공시 1건 처리: 제목 필터·나이·교차 중복 통과 시 알림 전송. */
    private void handle(KindDisclosure d, ZonedDateTime now) {
        Optional<NewsFilter.TitleMatch> match = newsFilter.matchTitle(d.title());
        if (match.isEmpty()) return;

        if (olderThanMaxAge(d.time(), now)) {
            log.info("KIND 나이 제한 제외 (게시 {}): {} - {}", d.time(), d.company(), d.title());
            return;
        }

        // DART 폴러가 먼저 알린 공시면 건너뛴다 — add가 원자적이라 한쪽만 true를 받는다.
        if (!disclosureKeys.add(DisclosureKeys.of(d.company(), d.title()))) {
            log.info("교차 중복 제외 (DART 선행 알림): {} - {}", d.company(), d.title());
            return;
        }

        log.info("호재 공시 감지 [KIND|{}|{}|{}]: {} - {}",
                d.market(), match.get().category(), match.get().matchedKeyword(), d.company(), d.title());
        notifier.send(alertComposer.compose(d, match.get()));
    }

    private static boolean olderThanMaxAge(String hhmm, ZonedDateTime now) {
        try {
            LocalTime published = LocalTime.parse(hhmm);
            ZonedDateTime publishedAt = now.with(published);
            return publishedAt.isBefore(now.minus(MAX_AGE));
        } catch (Exception e) {
            return false;  // 시각을 알 수 없으면 통과 — 재현율 우선
        }
    }
}
