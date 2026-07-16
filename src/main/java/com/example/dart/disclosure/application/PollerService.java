package com.example.dart.disclosure.application;

import com.example.dart.common.infra.PollWorker;
import com.example.dart.common.infra.RetryScheduler;
import com.example.dart.config.AppConfig;
import com.example.dart.disclosure.infra.DartClient;
import com.example.dart.disclosure.infra.DocumentNotReadyException;
import com.example.dart.disclosure.domain.NewsFilter;
import com.example.dart.disclosure.domain.Disclosure;
import com.example.dart.disclosure.application.AlertComposer;
import com.example.dart.notify.Notifier;
import com.example.dart.pricetrack.application.DisclosurePriceTracker;
import com.example.dart.disclosure.domain.DisclosureKeys;
import com.example.dart.common.infra.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

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

    /**
     * 시총·매출 보강 중복 차단용 키 접두어 — 같은 정정 공시가 접수번호만 다르게 여러 건 올라오면
     * (정규화 키 동일) winner·loser 양쪽이 scheduleScaleOnly로 들어와 보조 메시지가 중복 발송된다.
     * 헤더 교차중복 키(disclosureKeys)와 같은 store를 쓰되 접두어로 네임스페이스를 분리한다.
     */
    private static final String ENRICH_PREFIX = "ENRICH|";

    private final DartClient dartClient;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final AlertComposer alertComposer;
    private final SeenStore seenStore;
    private final SeenStore disclosureKeys;
    private final AppConfig config;
    private final DisclosurePriceTracker priceTracker;
    private final PollWorker scheduler;
    private final PollWorker enrichmentPool;
    /** 빠른 경로(KIND 본문) 재시도 — 짧게(10초×3회). 소진 시 DART 원문 경로로 폴백. */
    private final RetryScheduler fastRetry;
    /** DART 원문 재시도 — 원문 공개(014 해제)까지 길게(2분×10회). */
    private final RetryScheduler enrichRetry;

    public PollerService(DartClient dartClient, NewsFilter newsFilter,
                         Notifier notifier, AlertComposer alertComposer,
                         SeenStore seenStore, SeenStore disclosureKeys, AppConfig config,
                         DisclosurePriceTracker priceTracker) {
        this.dartClient = dartClient;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.seenStore = seenStore;
        this.disclosureKeys = disclosureKeys;
        this.config = config;
        this.priceTracker = priceTracker;
        this.scheduler = new PollWorker("dart-poller");
        this.enrichmentPool = new PollWorker("dart-enrich", 2);
        this.fastRetry = new RetryScheduler(enrichmentPool, FAST_RETRY_DELAY_SEC, FAST_MAX_ATTEMPTS);
        this.enrichRetry = new RetryScheduler(enrichmentPool, ENRICH_RETRY_DELAY_SEC, ENRICH_MAX_ATTEMPTS);
    }

    public void start() {
        log.info("폴링 시작 (주기: {}초)", config.pollIntervalSec());
        scheduler.scheduleWithFixedDelay(this::poll, 0, config.pollIntervalSec());
    }

    public void stop() {
        scheduler.stop();
        enrichmentPool.stop();
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

        // 공시 후 주가 추적(통계) — 호재 공시면 감지 시각 기준 10분간 주가 변동을 기록한다.
        // DART는 종목코드를 즉시 보유하므로 교차중복(DART/KIND 선행) 여부와 무관하게 여기서 추적한다.
        priceTracker.track(d, m.category());
        // "공시 2분 전 가격"을 한 줄로 후송한다 — 알림을 받자마자 어디서 출발했는지 가늠하게 한다.
        // KIS 조회는 별도 스레드에서 지연 실행되므로 아래 공시 헤더 발송을 1ms도 늦추지 않는다(공시 먼저, 가격 뒤).
        // 교차중복 여부와 무관하게 여기서 1회(감지당 1회) 예약한다.
        priceTracker.sendEntryPrice(d);

        // 교차 중복: add가 원자적이라 먼저 잡은 쪽만 true를 받는다. true면 DART가 먼저이니 DART가
        // 헤더+규모 분석(DART 원문)을 책임진다. false면 KIND가 먼저이고, KIND 폴러가 헤더와 규모 분석(KIND
        // 뷰어 본문)을 모두 담당하므로 — 각 소스가 자기 본문으로 보강 — DART는 여기서 손을 뗀다.
        boolean firstAlert = disclosureKeys.add(DisclosureKeys.of(d.rceptDt(), d.corpName(), d.reportNm()));
        boolean contract = NewsFilter.CATEGORY_CONTRACT.equals(m.category());
        // 정정 공시는 본문에 정정전/정정후 값이 섞여 계약금액 파싱이 틀리므로(비율 % 신뢰 불가)
        // 계약이어도 비율 분석을 생략하고 비계약 호재처럼 시총·매출만 보강한다.
        boolean correction = NewsFilter.isCorrection(d.reportNm());
        // 자기주식취득결정은 시총·매출에 더해 취득금액·시총대비%를 붙인다 — 전용 보강 경로(KIND→DART 원문 재시도).
        boolean treasury = NewsFilter.isTreasuryAcquisition(d.reportNm());
        // 자기주식 신탁계약 체결도 동일 경로로 신탁계약금액·시총대비%를 붙인다(금액 라벨만 계약금액으로 다름).
        boolean trust = NewsFilter.isTreasuryTrustContract(d.reportNm());
        // 주식소각결정은 소각예정금액·시총대비%를 붙인다. 계약처럼 각 폴러가 자기 본문으로 보강 —
        // KIND 선행이면 KIND 폴러가 담당하므로 DART는 생략하고, DART 선행이면 DART가 보강한다.
        boolean cancellation = NewsFilter.isStockCancellation(d.reportNm());
        if (!firstAlert) {
            // KIND가 헤더를 보냈다. 비정정 계약이면 KIND가 규모 분석(뷰어 본문)까지 담당하므로 DART는 빠진다.
            // 비계약·정정이면 KIND엔 매출 출처(corp_code)가 없으므로 시총·매출(자기주식은 취득금액까지)만 DART가 보강한다.
            if (contract && !correction) {
                log.info("KIND 선행 — KIND가 헤더·규모 분석 담당, DART 생략: {} - {}", d.corpName(), d.reportNm());
            } else if (treasury) {
                log.info("KIND 선행 — 취득금액·시총·매출 DART가 보강: {} - {}", d.corpName(), d.reportNm());
                scheduleTreasuryEnrichment(d, () -> alertComposer.composeTreasury(d));
            } else if (trust) {
                log.info("KIND 선행 — 신탁계약금액·시총·매출 DART가 보강: {} - {}", d.corpName(), d.reportNm());
                scheduleTreasuryEnrichment(d, () -> alertComposer.composeTreasuryTrust(d));
            } else if (cancellation) {
                // 소각은 계약처럼 KIND 폴러가 자기 본문으로 소각금액을 보강하므로 DART는 손을 뗀다.
                log.info("KIND 선행 — 소각금액은 KIND가 담당, DART 생략: {} - {}", d.corpName(), d.reportNm());
            } else {
                log.info("KIND 선행(비계약·정정) — 시총·매출만 DART가 보강: {} - {}", d.corpName(), d.reportNm());
                scheduleScaleOnly(d);
            }
            return;
        }

        // 1단계: 감지 즉시 헤더 전송
        log.info("호재 공시 감지 [{}|{}|{}]: {} - {}",
                d.marketName(), m.category(), m.matchedKeyword(), d.corpName(), d.reportNm());
        notifier.send(alertComposer.composeHeader(d, m));

        // 2단계: 보강 — 폴링 루프를 막지 않게 비동기로 후송.
        // 비정정 수주공급계약은 계약금액 대비 매출·시총(빠른 KIND 본문 우선, 실패 시 DART 원문),
        // 그 외 호재·정정은 회사 규모(시총·매출)만.
        if (contract && !correction) {
            scheduleFastEnrichment(d);
        } else if (treasury) {
            scheduleTreasuryEnrichment(d, () -> alertComposer.composeTreasury(d));
        } else if (trust) {
            scheduleTreasuryEnrichment(d, () -> alertComposer.composeTreasuryTrust(d));
        } else if (cancellation) {
            // DART 선행 — DART가 소각금액 보강(KIND 본문 우선→DART 원문 폴백). 재시도·폴백 로직 공유.
            scheduleTreasuryEnrichment(d, () -> alertComposer.composeCancellation(d));
        } else {
            scheduleScaleOnly(d);
        }
    }

    /**
     * 빠른 규모 분석 — KIND 뷰어 본문으로 즉시 보강한다(DART 원문 지연 회피). DART가 먼저 감지한 계약도
     * 헤더 직후 수초 내 %를 받게 한다. KIND가 아직 목록에 없거나 본문 조회가 실패하면 짧게 재시도하고,
     * 끝내 못 구하면 기존 DART 원문 경로({@link #scheduleEnrichment})로 폴백한다.
     */
    private void scheduleFastEnrichment(Disclosure d) {
        fastRetry.run(() -> notifier.send(alertComposer.composeFollowupFast(d)),
                (e, attempt) -> log.info("빠른 KIND 보강 실패 — {}초 뒤 재시도 ({}/{}): {} - {} ({})",
                        FAST_RETRY_DELAY_SEC, attempt, FAST_MAX_ATTEMPTS, d.corpName(), d.reportNm(), e.toString()),
                e -> {
                    log.info("빠른 KIND 보강 {}회 실패 — DART 원문 경로로 폴백: {} - {}",
                            FAST_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    scheduleEnrichment(d);
                });
    }

    /**
     * 계약이 아닌 호재용 — 회사 규모(시총·매출)만 보강해 전송한다. 원문을 거치지 않아 014·재시도가 없다.
     * KIND 선행 비계약 공시도 매출 출처(corp_code)가 DART에만 있으므로 이 경로로 보강한다.
     */
    private void scheduleScaleOnly(Disclosure d) {
        // 같은 키(날짜+회사+정규화 제목)의 시총·매출 보강은 1회만 — 정정 공시가 접수번호만 다르게
        // 2건 올라온 경우(winner=151, loser=135 모두 이 경로) 중복 발송을 막는다.
        if (!disclosureKeys.add(ENRICH_PREFIX + DisclosureKeys.of(d.rceptDt(), d.corpName(), d.reportNm()))) {
            log.info("시총·매출 보강 중복 생략(동일 키 재발생): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        enrichmentPool.submit(() -> {
            try {
                notifier.send(alertComposer.composeScaleOnly(d));
            } catch (Exception e) {
                log.warn("시총·매출 보강 실패 — 헤더 알림은 이미 전송됨: {} - {}", d.corpName(), d.reportNm(), e);
            }
        });
    }

    /**
     * 자기주식취득결정 보강 — 시총·매출에 더해 취득금액·시총대비%를 전송한다. 취득금액은 KIND 뷰어 본문
     * 우선, 실패 시 DART 원문에서 파싱하는데, 원문이 아직 미공개(014)면 {@link DocumentNotReadyException}이
     * 올라오므로 일정 간격으로 재조회한다(계약 보강과 동일). 끝내 못 구하면 시총·매출만 발송한다.
     */
    private void scheduleTreasuryEnrichment(Disclosure d, Supplier<String> composer) {
        // 같은 키의 보강은 1회만 — scheduleScaleOnly와 같은 네임스페이스(접두어)로 중복 발송을 막는다.
        if (!disclosureKeys.add(ENRICH_PREFIX + DisclosureKeys.of(d.rceptDt(), d.corpName(), d.reportNm()))) {
            log.info("취득금액 보강 중복 생략(동일 키 재발생): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        enrichRetry.run(() -> notifier.send(composer.get()),
                e -> e instanceof DocumentNotReadyException,
                (e, attempt) -> log.info("취득금액 원문 미공개 — {}초 뒤 재조회 ({}/{}): {} - {}",
                        ENRICH_RETRY_DELAY_SEC, attempt, ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm()),
                e -> {
                    log.warn("취득금액 원문 미공개 — 재조회 {}회 실패, 시총·매출만 발송: {} - {}",
                            ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    notifier.send(alertComposer.composeScaleOnly(d));
                },
                e -> {
                    log.warn("취득금액 보강 실패 — 시총·매출만 발송: {} - {}", d.corpName(), d.reportNm(), e);
                    notifier.send(alertComposer.composeScaleOnly(d));
                });
    }

    /**
     * 규모 분석 후속 메시지를 보강해 전송한다. 원문이 아직 공개되지 않으면(014)
     * {@link DocumentNotReadyException}이 올라오므로, 일정 간격으로 재시도한다.
     */
    private void scheduleEnrichment(Disclosure d) {
        enrichRetry.run(() -> notifier.send(alertComposer.composeFollowup(d)),
                e -> e instanceof DocumentNotReadyException,
                (e, attempt) -> log.info("원문 미공개 — {}초 뒤 재조회 ({}/{}): {} - {}",
                        ENRICH_RETRY_DELAY_SEC, attempt, ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm()),
                e -> {
                    log.warn("원문 미공개 — 재조회 {}회 모두 실패, 규모 분석 생략: {} - {}",
                            ENRICH_MAX_ATTEMPTS, d.corpName(), d.reportNm());
                    notifier.send(String.format("📊 **시총·매출 대비** | %s — %s\n원문 미공개 — 규모 분석 생략",
                            d.corpName(), d.reportNm()));
                },
                e -> log.warn("보강 알림 전송 실패: {} - {}", d.corpName(), d.reportNm(), e));
    }
}
