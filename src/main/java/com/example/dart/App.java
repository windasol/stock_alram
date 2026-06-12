package com.example.dart;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.news.NaverNewsClient;
import com.example.dart.news.NewsAlertComposer;
import com.example.dart.news.NewsArticleFilter;
import com.example.dart.news.NewsKeywordClassifier;
import com.example.dart.news.NewsPollerService;
import com.example.dart.news.RssClient;
import com.example.dart.news.RssFeed;
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

import java.nio.file.Path;
import java.time.Duration;

/** 조립 루트 — 의존성 생성·연결과 생명주기 관리만 담당한다. */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("DART 실시간 호재 알림 봇 시작");

        AppConfig config = AppConfig.load();
        DartClient dartClient = new DartClient(config.dartApiKey());
        NewsFilter newsFilter = new NewsFilter(config.filterExtraKeywords(), config.filterExcludeKeywords());
        SeenStore seenStore = new SeenStore(Path.of("seen.txt"));
        DocumentService documentService = new DocumentService(dartClient, new DocumentParser());
        AlertComposer alertComposer = new AlertComposer(documentService);

        Notifier notifier = createNotifier(config, null);

        // 뉴스 전용 채널이 설정되면 분리 — 공시(소량·확정)와 뉴스(다량·속보)를 섞지 않는다.
        String newsChannelId = config.newsChannelId();
        Notifier newsNotifier = (newsChannelId != null)
                ? createNotifier(config, newsChannelId)
                : notifier;

        boolean separateNewsChannel = newsNotifier != notifier;

        notifier.start();
        // 뉴스 채널이 분리됐으면 공시 채널엔 공시만 언급한다.
        notifier.send(config.newsEnabled() && !separateNewsChannel
                ? "📋 공시 + 뉴스 알림이 시작되었습니다."
                : "📋 공시 알림이 시작되었습니다.");
        if (separateNewsChannel) {
            newsNotifier.start();
            newsNotifier.send("📰 뉴스 알림이 시작되었습니다. (🟢 호재 / 🔴 악재 / 🌐 시황)");
            log.info("뉴스 알림 채널 분리 (channel/room: {})", newsChannelId);
        }

        PollerService pollerService = new PollerService(
                dartClient, newsFilter, notifier, documentService, alertComposer, seenStore, config);
        pollerService.start();

        // 뉴스 폴러 — 공시 폴러와 별도 스레드에서 병렬 동작, Notifier만 공유.
        // RSS는 항상, 네이버 검색은 키가 있을 때만 보완망으로 동작.
        NewsPollerService newsPollerService;
        if (config.newsEnabled()) {
            NaverNewsClient newsClient = config.naverEnabled()
                    ? new NaverNewsClient(config.naverClientId(), config.naverClientSecret())
                    : null;
            if (newsClient == null) {
                log.info("네이버 검색 보완망 비활성화 (NAVER_CLIENT_ID/SECRET 미설정) — RSS만 사용");
            }
            NewsKeywordClassifier classifier = new NewsKeywordClassifier(
                    config.newsKeywords(), config.newsBadKeywords(), config.newsMacroKeywords(),
                    config.newsMacroTopics(), config.newsMacroTriggers(), config.newsFlipKeywords());
            NewsArticleFilter articleFilter = new NewsArticleFilter(
                    config.newsExcludeKeywords(), Duration.ofMinutes(config.newsMaxAgeMin()),
                    Duration.ofMinutes(config.newsMacroCooldownMin()));
            SeenStore newsSeenStore = new SeenStore(Path.of("seen_news.txt"));
            newsPollerService = new NewsPollerService(
                    new RssClient(), RssFeed.parseList(config.newsRssFeeds()),
                    newsClient, classifier, articleFilter, newsNotifier,
                    new NewsAlertComposer(), newsSeenStore, config);
            newsPollerService.start();
        } else {
            newsPollerService = null;
            log.info("뉴스 폴링 비활성화 (NEWS_ENABLED=false)");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("종료 신호 수신, 서비스 중지 중...");
            pollerService.stop();
            if (newsPollerService != null) newsPollerService.stop();
            notifier.stop();
            if (newsNotifier != notifier) newsNotifier.stop();
            log.info("봇 종료 완료");
        }));
    }

    /** @param channelOverride 채널/룸 ID 재지정 (null이면 기본 채널) */
    private static Notifier createNotifier(AppConfig config, String channelOverride) {
        return switch (config.notifier()) {
            case "discord" -> new DiscordService(config.discordBotToken(),
                    channelOverride != null ? channelOverride : config.discordChannelId());
            case "webex"   -> new WebexService(config.webexBotToken(),
                    channelOverride != null ? channelOverride : config.webexRoomId());
            default -> throw new IllegalStateException("알 수 없는 NOTIFIER: " + config.notifier());
        };
    }
}
