package com.example.dart;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.notify.AlertComposer;
import com.example.dart.notify.DiscordService;
import com.example.dart.notify.Notifier;
import com.example.dart.notify.WebexService;
import com.example.dart.parse.DocumentParser;
import com.example.dart.service.DocumentService;
import com.example.dart.service.PollerService;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 조립 루트 — 의존성 생성·연결과 생명주기 관리만 담당한다. */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("DART 실시간 호재 알림 봇 시작");

        AppConfig config = AppConfig.load();
        DartClient dartClient = new DartClient(config.dartApiKey());
        NewsFilter newsFilter = new NewsFilter(config.filterExtraKeywords(), config.filterExcludeKeywords());
        SeenStore seenStore = new SeenStore();
        DocumentService documentService = new DocumentService(dartClient, new DocumentParser());
        AlertComposer alertComposer = new AlertComposer(documentService);

        Notifier notifier = switch (config.notifier()) {
            case "discord" -> new DiscordService(config.discordBotToken(), config.discordChannelId());
            case "webex"   -> new WebexService(config.webexBotToken(), config.webexRoomId());
            default -> throw new IllegalStateException("알 수 없는 NOTIFIER: " + config.notifier());
        };

        notifier.start();
        notifier.send("DART 호재 알림 봇이 시작되었습니다.");

        PollerService pollerService = new PollerService(
                dartClient, newsFilter, notifier, documentService, alertComposer, seenStore, config);
        pollerService.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("종료 신호 수신, 서비스 중지 중...");
            pollerService.stop();
            notifier.stop();
            log.info("봇 종료 완료");
        }));
    }
}
