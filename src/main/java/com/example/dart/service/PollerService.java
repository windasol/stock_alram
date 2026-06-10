package com.example.dart.service;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.notify.DiscordService;
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
    private final DiscordService discordService;
    private final SeenStore seenStore;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    public PollerService(DartClient dartClient, NewsFilter newsFilter,
                         DiscordService discordService, SeenStore seenStore, AppConfig config) {
        this.dartClient = dartClient;
        this.newsFilter = newsFilter;
        this.discordService = discordService;
        this.seenStore = seenStore;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dart-poller");
            t.setDaemon(true);
            return t;
        });
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
            List<Disclosure> disclosures = dartClient.fetchRecent(config.corpCls());
            for (Disclosure d : disclosures) {
                if (seenStore.contains(d.rceptNo())) continue;
                seenStore.add(d.rceptNo());

                if (newsFilter.isGoodNews(d.reportNm())) {
                    log.info("호재 공시 감지: {} - {}", d.corpName(), d.reportNm());
                    discordService.sendTitleAlert(d);
                }
            }
        } catch (Exception e) {
            log.error("폴링 중 오류 발생", e);
        }
    }
}
