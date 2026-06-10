package com.example.dart.service;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.notify.AlertComposer;
import com.example.dart.notify.Notifier;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 주기적으로 DART 최신 공시를 폴링해 호재를 골라 알림을 보낸다.
 * 흐름: 신규 공시 → Stage 1 제목 필터 → Stage 2 본문 필터 → 메시지 조립 → 전송.
 */
public class PollerService {

    private static final Logger log = LoggerFactory.getLogger(PollerService.class);

    private final DartClient dartClient;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final DocumentService documentService;
    private final AlertComposer alertComposer;
    private final SeenStore seenStore;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    public PollerService(DartClient dartClient, NewsFilter newsFilter,
                         Notifier notifier, DocumentService documentService,
                         AlertComposer alertComposer, SeenStore seenStore, AppConfig config) {
        this.dartClient = dartClient;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.documentService = documentService;
        this.alertComposer = alertComposer;
        this.seenStore = seenStore;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "dart-poller"));
    }

    public void start() {
        log.info("폴링 시작 (주기: {}초)", config.pollIntervalSec());
        scheduler.scheduleWithFixedDelay(this::poll, 0, config.pollIntervalSec(), TimeUnit.SECONDS);
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

    /** 신규 공시 1건 처리: 필터 통과 시 알림 전송. */
    private void handle(Disclosure d) {
        // Stage 1: 공시 제목 필터 (빠름, 네트워크 없음)
        Optional<NewsFilter.TitleMatch> match = newsFilter.matchTitle(d.reportNm());
        if (match.isEmpty()) return;

        // Stage 2: 본문 필터 (수주공급계약 한정 — 조건부 계약, 매출액 비율)
        if (rejectedByBody(d, match.get())) return;

        log.info("호재 공시 감지 [{}|{}|{}]: {} - {}",
                d.marketName(), match.get().category(), match.get().matchedKeyword(), d.corpName(), d.reportNm());
        notifier.send(alertComposer.compose(d, match.get()));
    }

    private boolean rejectedByBody(Disclosure d, NewsFilter.TitleMatch match) {
        try {
            String bodyText = documentService.toPlainText(d.rceptNo());
            Optional<String> reject = newsFilter.bodyRejectReason(bodyText, match);
            if (reject.isPresent()) {
                log.info("본문 필터 제외 [{}]: {} - {}", reject.get(), d.corpName(), d.reportNm());
                return true;
            }
        } catch (Exception e) {
            // 본문 조회 실패 시 제목 기준으로 알림 (놓치지 않음)
            log.warn("본문 조회 실패, 제목 기준으로 알림: {} - {}", d.corpName(), d.reportNm());
        }
        return false;
    }
}
