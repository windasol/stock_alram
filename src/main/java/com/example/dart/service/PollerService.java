package com.example.dart.service;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.dart.DocumentNotReadyException;
import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.notify.AlertComposer;
import com.example.dart.notify.Notifier;
import com.example.dart.util.DisclosureKeys;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 주기적으로 DART 최신 공시를 폴링해 호재를 골라 알림을 보낸다.
 *
 * 공시는 속도가 생명이라 2단계로 알린다:
 *  1) 제목 필터 통과 + 교차중복 아님 → 헤더를 감지 즉시 전송 (본문 조회 없음).
 *  2) 규모 분석(본문·계약금액·매출/시총 대비)은 별도 스레드에서 보강 메시지로 후송 —
 *     폴링 루프를 막지 않아 다음 공시 처리가 지연되지 않는다.
 * (기존 본문 필터 게이트는 제거 — 소규모·조건부 계약도 일단 알리고 후속에서 규모를 표시한다.)
 */
public class PollerService {

    private static final Logger log = LoggerFactory.getLogger(PollerService.class);

    /**
     * DART 원문(document.xml)은 목록 등재보다 수 분~수 시간 늦게 공개된다(그 전엔 status 014).
     * 감지 직후엔 거의 항상 미공개이므로, 원문이 풀릴 때까지 일정 간격으로 재조회한다.
     */
    private static final long ENRICH_RETRY_DELAY_SEC = 120;   // 2분 간격
    private static final int ENRICH_MAX_ATTEMPTS = 10;        // 최대 10회 ≈ 20분 커버

    /**
     * 빠른 경로(KIND 뷰어 본문) — DART가 먼저 잡은 계약도 헤더 직후 수초 내 %를 받게 한다.
     * KIND가 아직 목록에 안 올렸을 수 있어 짧게 재시도하고, 그래도 못 구하면 DART 원문 경로로 폴백한다.
     */
    private static final long FAST_RETRY_DELAY_SEC = 10;
    private static final int FAST_MAX_ATTEMPTS = 3;           // ≈ 20초 커버

    private final DartClient dartClient;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final AlertComposer alertComposer;
    private final SeenStore seenStore;
    private final SeenStore disclosureKeys;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService enrichmentPool;

    public PollerService(DartClient dartClient, NewsFilter newsFilter,
                         Notifier notifier, AlertComposer alertComposer,
                         SeenStore seenStore, SeenStore disclosureKeys, AppConfig config) {
        this.dartClient = dartClient;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.seenStore = seenStore;
        this.disclosureKeys = disclosureKeys;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "dart-poller"));
        this.enrichmentPool = Executors.newScheduledThreadPool(2, r -> new Thread(r, "dart-enrich"));
    }

    public void start() {
        log.info("폴링 시작 (주기: {}초)", config.pollIntervalSec());
        scheduler.scheduleWithFixedDelay(this::poll, 0, config.pollIntervalSec(), TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdown();
        enrichmentPool.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!enrichmentPool.awaitTermination(5, TimeUnit.SECONDS)) {
                enrichmentPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            enrichmentPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("폴링 중지 완료");
    }

    private void poll() {
        try {
            for (Disclosure d : dartClient.fetchRecent(config.corpCls(), config.pblntfTy())) {
                if (seenStore.contains(d.rceptNo())) continue;
                seenStore.add(d.rceptNo());
                handle(d);
            }
        } catch (Exception e) {
            log.error("폴링 중 오류 발생", e);
        }
    }

    /** 신규 공시 1건 처리: 제목 필터 통과 시 헤더 즉시 전송 + 규모 분석 후송. */
    private void handle(Disclosure d) {
        // Stage 1: 공시 제목 필터 (빠름, 네트워크 없음)
        Optional<NewsFilter.TitleMatch> match = newsFilter.matchTitle(d.reportNm());
        if (match.isEmpty()) return;
        NewsFilter.TitleMatch m = match.get();

        // 교차 중복: add가 원자적이라 먼저 잡은 쪽만 true를 받는다. true면 DART가 먼저이니 DART가
        // 헤더+규모 분석(DART 원문)을 책임진다. false면 KIND가 먼저이고, KIND 폴러가 헤더와 규모 분석(KIND
        // 뷰어 본문)을 모두 담당하므로 — 각 소스가 자기 본문으로 보강 — DART는 여기서 손을 뗀다.
        boolean firstAlert = disclosureKeys.add(DisclosureKeys.of(d.rceptDt(), d.corpName(), d.reportNm()));
        boolean contract = NewsFilter.CATEGORY_CONTRACT.equals(m.category());
        if (!firstAlert) {
            // KIND가 헤더를 보냈다. 계약이면 KIND가 규모 분석(뷰어 본문)까지 담당하므로 DART는 빠진다.
            // 비계약이면 KIND엔 매출 출처(corp_code)가 없으므로 시총·매출만은 DART가 보강한다.
            if (contract) {
                log.info("KIND 선행 — KIND가 헤더·규모 분석 담당, DART 생략: {} - {}", d.corpName(), d.reportNm());
            } else {
                log.info("KIND 선행(비계약) — 시총·매출만 DART가 보강: {} - {}", d.corpName(), d.reportNm());
                scheduleScaleOnly(d);
            }
            return;
        }

        // 1단계: 감지 즉시 헤더 전송
        log.info("호재 공시 감지 [{}|{}|{}]: {} - {}",
                d.marketName(), m.category(), m.matchedKeyword(), d.corpName(), d.reportNm());
        notifier.send(alertComposer.composeHeader(d, m));

        // 2단계: 보강 — 폴링 루프를 막지 않게 비동기로 후송.
        // 수주공급계약은 계약금액 대비 매출·시총(빠른 KIND 본문 우선, 실패 시 DART 원문), 그 외 호재는 회사 규모(시총·매출)만.
        if (contract) {
            scheduleFastEnrichment(d, 1);
        } else {
            scheduleScaleOnly(d);
        }
    }

    /**
     * 빠른 규모 분석 — KIND 뷰어 본문으로 즉시 보강한다(DART 원문 지연 회피). DART가 먼저 감지한 계약도
     * 헤더 직후 수초 내 %를 받게 한다. KIND가 아직 목록에 없거나 본문 조회가 실패하면 짧게 재시도하고,
     * 끝내 못 구하면 기존 DART 원문 경로({@link #scheduleEnrichment})로 폴백한다.
     */
    private void scheduleFastEnrichment(Disclosure d, int attempt) {
        enrichmentPool.submit(() -> {
            try {
                notifier.send(alertComposer.composeFollowupFast(d));
            } catch (Exception e) {
                if (attempt < FAST_MAX_ATTEMPTS) {
                    log.info("빠른 KIND 보강 실패 — {}초 뒤 재시도 ({}/{}): {} - {} ({})",
                            FAST_RETRY_DELAY_SEC, attempt, FAST_MAX_ATTEMPTS, d.corpName(), d.reportNm(), e.toString());
                    enrichmentPool.schedule(() -> scheduleFastEnrichment(d, attempt + 1),
                            FAST_RETRY_DELAY_SEC, TimeUnit.SECONDS);
                } else {
                    log.info("빠른 KIND 보강 {}회 실패 — DART 원문 경로로 폴백: {} - {}",
                            FAST_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    scheduleEnrichment(d, 1);
                }
            }
        });
    }

    /**
     * 계약이 아닌 호재용 — 회사 규모(시총·매출)만 보강해 전송한다. 원문을 거치지 않아 014·재시도가 없다.
     * KIND 선행 비계약 공시도 매출 출처(corp_code)가 DART에만 있으므로 이 경로로 보강한다.
     */
    private void scheduleScaleOnly(Disclosure d) {
        enrichmentPool.submit(() -> {
            try {
                notifier.send(alertComposer.composeScaleOnly(d));
            } catch (Exception e) {
                log.warn("시총·매출 보강 실패 — 헤더 알림은 이미 전송됨: {} - {}", d.corpName(), d.reportNm(), e);
            }
        });
    }

    /**
     * 규모 분석 후속 메시지를 보강해 전송한다. 원문이 아직 공개되지 않으면(014)
     * {@link DocumentNotReadyException}이 올라오므로, 일정 간격으로 재시도한다.
     */
    private void scheduleEnrichment(Disclosure d, int attempt) {
        enrichmentPool.submit(() -> {
            try {
                notifier.send(alertComposer.composeFollowup(d));
            } catch (DocumentNotReadyException e) {
                if (attempt < ENRICH_MAX_ATTEMPTS) {
                    log.info("원문 미공개 — {}초 뒤 재조회 ({}/{}): {} - {}",
                            ENRICH_RETRY_DELAY_SEC, attempt, ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    enrichmentPool.schedule(() -> scheduleEnrichment(d, attempt + 1),
                            ENRICH_RETRY_DELAY_SEC, TimeUnit.SECONDS);
                } else {
                    log.warn("원문 미공개 — 재조회 {}회 모두 실패, 규모 분석 생략: {} - {}",
                            ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    notifier.send(String.format("📊 **시총·매출 대비** | %s — %s\n원문 미공개 — 규모 분석 생략",
                            d.corpName(), d.reportNm()));
                }
            } catch (Exception e) {
                log.warn("보강 알림 전송 실패: {} - {}", d.corpName(), d.reportNm(), e);
            }
        });
    }
}
