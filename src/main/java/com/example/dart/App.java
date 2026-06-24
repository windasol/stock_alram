package com.example.dart;

import com.example.dart.config.AppConfig;
import com.example.dart.dart.DartClient;
import com.example.dart.filter.NewsFilter;
import com.example.dart.kind.KindAlertComposer;
import com.example.dart.kind.KindClient;
import com.example.dart.kind.KindDocumentClient;
import com.example.dart.kind.KindPollerService;
import com.example.dart.kis.KisAlertComposer;
import com.example.dart.kis.KisClient;
import com.example.dart.kis.KisPollerService;
import com.example.dart.news.GoogleNewsFeeds;
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
import com.example.dart.quote.StockQuoteClient;
import com.example.dart.service.DisclosurePriceTracker;
import com.example.dart.service.DocumentService;
import com.example.dart.service.PollerService;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** 조립 루트 — 의존성 생성·연결과 생명주기 관리만 담당한다. */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /**
     * 단일 인스턴스 보장용 락. JVM 수명 동안 잡고 있어야 하므로 정적 참조로 유지한다
     * (지역 변수면 GC되어 채널이 닫히고 락이 풀린다).
     */
    private static FileChannel lockChannel;
    @SuppressWarnings("unused") // 락 유지 목적의 참조 — 직접 사용하진 않는다.
    private static FileLock instanceLock;

    public static void main(String[] args) {
        log.info("DART 실시간 호재 알림 봇 시작");
        ensureSingleInstance();

        AppConfig config = AppConfig.load();
        DartClient dartClient = new DartClient(config.dartApiKey());
        NewsFilter newsFilter = new NewsFilter(config.filterExtraKeywords(), config.filterExcludeKeywords());
        SeenStore seenStore = new SeenStore(Path.of("seen.txt"));
        DocumentParser documentParser = new DocumentParser();
        DocumentService documentService = new DocumentService(dartClient, documentParser);
        StockQuoteClient quoteClient = new StockQuoteClient();
        // KIND 소스 — DART가 먼저 잡은 계약을 KIND 뷰어 본문으로 즉시 보강하는 빠른 경로에서 쓰고,
        // KIND 폴러에서도 같은 인스턴스를 재사용한다. KIND 비활성 시 null이면 빠른 경로는 즉시 폴백한다.
        KindClient kindClient = config.kindEnabled() ? new KindClient() : null;
        KindDocumentClient kindDocumentClient = config.kindEnabled() ? new KindDocumentClient() : null;
        AlertComposer alertComposer = new AlertComposer(documentService, newsFilter, quoteClient, dartClient,
                kindClient, kindDocumentClient, documentParser);

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

        // DART와 KIND가 같은 공시를 각각 게시하므로, 공유 키 저장소로 먼저 잡은 쪽만 알린다.
        SeenStore disclosureKeys = new SeenStore(Path.of("seen_disclosure_keys.txt"));

        // KIS 클라이언트 — 급등 폴러와 주가추적(NXT 호가 보완)이 같은 인스턴스를 공유한다.
        // 토큰은 분당 1회 발급 한도라 인스턴스를 나누면 안 된다. KIS 미설정이면 null이고, 그땐
        // 주가추적은 호가 없이 체결가/종가만으로 동작한다.
        KisClient kisClient = config.kisEnabled()
                ? new KisClient(config.kisAppKey(), config.kisAppSecret(),
                        config.kisMarketDivCode(), config.kisPaper(), Path.of("kis_token.txt"))
                : null;

        // 공시 후 주가 추적(통계) — 호재 공시 감지 시각 기준 10분간 주가 변동을 기록·요약한다.
        DisclosurePriceTracker priceTracker = new DisclosurePriceTracker(
                quoteClient, kisClient, notifier, Path.of("disclosure_price_stats.jsonl"));

        PollerService pollerService = new PollerService(
                dartClient, newsFilter, notifier, alertComposer,
                seenStore, disclosureKeys, config, priceTracker);
        pollerService.start();

        // KIND 폴러 — 거래소 공시는 KIND에 먼저 게시되는 경우가 많아 가장 빠른 공시 소스.
        KindPollerService kindPollerService;
        if (config.kindEnabled()) {
            kindPollerService = new KindPollerService(
                    kindClient, newsFilter, notifier, new KindAlertComposer(),
                    kindDocumentClient, documentParser, quoteClient,
                    new SeenStore(Path.of("seen_kind.txt")), disclosureKeys, config);
            kindPollerService.start();
        } else {
            kindPollerService = null;
            log.info("KIND 폴링 비활성화 (KIND_ENABLED=false)");
        }

        // KIS 변동성 폴러 — 거래량 폭증(평소 대비 RVOL) + 급등 종목을 정찰. 키는 시스템 환경변수로 주입.
        // 전용 채널이 설정되면 분리(공시·뉴스와 성격이 다른 시세 반응 신호라 섞지 않는다), 아니면 공시 채널 공유.
        KisPollerService kisPollerService;
        Notifier kisNotifier = notifier;
        boolean separateKisChannel = false;
        if (config.kisEnabled()) {
            String kisChannelId = config.kisChannelId();
            if (kisChannelId != null) {
                kisNotifier = createNotifier(config, kisChannelId);
                separateKisChannel = true;
                kisNotifier.start();
                kisNotifier.send("🚨 급등 알림이 시작되었습니다.");
                log.info("KIS 알림 채널 분리 (channel/room: {})", kisChannelId);
            }
            kisPollerService = new KisPollerService(
                    kisClient,   // 주가추적과 공유 — 토큰 분당 1회 발급 한도 때문에 인스턴스를 나누지 않는다.
                    kisNotifier, new KisAlertComposer(), config, Path.of("kis_sectors.txt"));
            kisPollerService.start();
        } else {
            kisPollerService = null;
            log.info("KIS 변동성 폴링 비활성화 (KIS_APP_KEY/KIS_APP_SECRET 미설정)");
        }

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
                    Duration.ofMinutes(config.newsMacroCooldownMin()),
                    Path.of("seen_news_titles.txt"));
            SeenStore newsSeenStore = new SeenStore(Path.of("seen_news.txt"));
            newsPollerService = new NewsPollerService(
                    new RssClient(), RssFeed.parseList(config.newsRssFeeds()),
                    GoogleNewsFeeds.of(config.newsGoogleKeywords()),
                    newsClient, classifier, articleFilter, newsNotifier,
                    new NewsAlertComposer(), newsSeenStore, config);
            newsPollerService.start();
        } else {
            newsPollerService = null;
            log.info("뉴스 폴링 비활성화 (NEWS_ENABLED=false)");
        }

        // 람다 캡처용 final 참조 (kisNotifier는 위에서 재할당될 수 있어 effectively final이 아님).
        final Notifier kisNotifierRef = kisNotifier;
        final boolean separateKisChannelRef = separateKisChannel;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("종료 신호 수신, 서비스 중지 중...");
            pollerService.stop();
            priceTracker.stop();
            if (kindPollerService != null) kindPollerService.stop();
            if (kisPollerService != null) kisPollerService.stop();
            if (newsPollerService != null) newsPollerService.stop();
            notifier.stop();
            if (newsNotifier != notifier) newsNotifier.stop();
            if (separateKisChannelRef) kisNotifierRef.stop();
            log.info("봇 종료 완료");
        }));
    }

    /** @param channelOverride 채널/룸 ID 재지정 (null이면 기본 채널) */
    /**
     * 인스턴스가 하나만 뜨도록 파일 락을 건다. 이미 다른 프로세스가 잡고 있으면 즉시 종료한다.
     * 두 인스턴스가 동시에 돌면 각자 메모리 중복필터(SeenStore·NewsArticleFilter.recentAlerts)를
     * 따로 들고 있어 서로의 알림을 못 막고, 같은 기사를 인스턴스 수만큼 중복 전송하기 때문이다.
     */
    private static void ensureSingleInstance() {
        Path lockFile = Path.of("app.lock");
        try {
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            if (instanceLock == null) {
                log.error("이미 다른 인스턴스가 실행 중입니다 ({}). 중복 알림 방지를 위해 종료합니다 — "
                        + "기존 프로세스를 먼저 종료하세요.", lockFile.toAbsolutePath());
                System.exit(1);
            }
            log.info("단일 인스턴스 락 획득 ({})", lockFile.toAbsolutePath());
        } catch (OverlappingFileLockException e) {
            log.error("이미 다른 인스턴스가 실행 중입니다 ({}). 중복 알림 방지를 위해 종료합니다.",
                    lockFile.toAbsolutePath());
            System.exit(1);
        } catch (IOException e) {
            log.error("인스턴스 락 획득 실패 ({}) — 단일 인스턴스 보장 없이 계속 진행합니다.", lockFile, e);
        }
    }

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
