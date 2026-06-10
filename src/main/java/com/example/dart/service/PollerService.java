package com.example.dart.service;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.notify.Notifier;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PollerService {

    private static final Logger log = LoggerFactory.getLogger(PollerService.class);

    private final DartClient dartClient;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final DocumentService documentService;
    private final SeenStore seenStore;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    public PollerService(DartClient dartClient, NewsFilter newsFilter,
                         Notifier notifier, DocumentService documentService,
                         SeenStore seenStore, AppConfig config) {
        this.dartClient = dartClient;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.documentService = documentService;
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
            List<Disclosure> disclosures = dartClient.fetchRecent(config.corpCls(), config.pblntfTy());
            for (Disclosure d : disclosures) {
                if (seenStore.contains(d.rceptNo())) continue;
                seenStore.add(d.rceptNo());

                // Stage 1: 공시 제목 필터 (빠름, 네트워크 없음)
                if (!newsFilter.isGoodNewsTitle(d.reportNm())) continue;

                // Stage 2: 본문 필터 (조건부 제외, 계약금액 비율 검증)
                try {
                    String bodyText = documentService.toPlainText(d.rceptNo());
                    if (!newsFilter.isGoodNewsBody(bodyText)) {
                        log.debug("본문 필터 제외: {} - {}", d.corpName(), d.reportNm());
                        continue;
                    }
                } catch (Exception e) {
                    // 본문 조회 실패 시 제목 기준으로 알림 (놓치지 않음)
                    log.warn("본문 조회 실패, 제목 기준으로 알림: {} - {}", d.corpName(), d.reportNm());
                }

                log.info("호재 공시 감지: {} - {}", d.corpName(), d.reportNm());
                notifier.sendTitleAlert(d);
            }
        } catch (Exception e) {
            log.error("폴링 중 오류 발생", e);
        }
    }
}
