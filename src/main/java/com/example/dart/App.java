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
import com.example.dart.llm.GeminiClient;
import com.example.dart.llm.LlmClient;
import com.example.dart.llm.OllamaClient;
import com.example.dart.news.GoogleNewsFeeds;
import com.example.dart.news.NaverNewsClient;
import com.example.dart.news.NewsHeadlineBuffer;
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
import com.example.dart.trade.AutoTradeService;
import com.example.dart.util.MarketCalendar;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
            newsNotifier.send("🗞️ 시황·뉴스 종합 리포트 채널이 시작되었습니다. (지난 1시간 뉴스를 시황 분석에 녹여 시간당 발송)");
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

        // 거래일 캘린더 — 캐시 파일(krx_holidays.txt)의 휴장일을 읽고, KIS가 있으면 실측 휴장일을 조회해 최신화한다.
        // 공휴일 데이터가 비면 주말만 걸러 기존 동작으로 안전하게 폴백한다.
        MarketCalendar marketCalendar = buildMarketCalendar(kisClient);

        // 공시 후 주가 추적(통계) — 호재 공시 감지 시각 기준 10분간 주가 변동을 기록·요약한다.
        DisclosurePriceTracker priceTracker = new DisclosurePriceTracker(
                quoteClient, kisClient, notifier, Path.of("disclosure_price_stats.jsonl"), marketCalendar);

        // 공시 기반 자동매매(드라이런) — 계약 규모(매출 대비 ≥임계%) 신호를 두 공시 소스(DART AlertComposer / KIND)에서
        // 받아 모의 매매한다. 현재가는 KIS 분봉으로 조회하므로 kisClient가 있어야 동작한다. 비활성/키 없음이면 리스너 null(무동작).
        KindAlertComposer kindAlertComposer = new KindAlertComposer();
        AutoTradeService autoTrader = null;
        if (config.autoTradeEnabled() && kisClient != null) {
            autoTrader = new AutoTradeService(kisClient, notifier, marketCalendar, config);
            alertComposer.setTradeSignalListener(autoTrader);   // DART 경로 훅(AlertComposer.buildFollowup)
            autoTrader.start();
        } else if (config.autoTradeEnabled()) {
            log.warn("자동매매 활성이지만 KIS 미설정 — 현재가 조회 불가로 비활성. KIS_APP_KEY/KIS_APP_SECRET 확인");
        }

        PollerService pollerService = new PollerService(
                dartClient, newsFilter, notifier, alertComposer,
                seenStore, disclosureKeys, config, priceTracker);
        pollerService.start();

        // KIND 폴러 — 거래소 공시는 KIND에 먼저 게시되는 경우가 많아 가장 빠른 공시 소스.
        KindPollerService kindPollerService;
        if (config.kindEnabled()) {
            kindPollerService = new KindPollerService(
                    kindClient, newsFilter, notifier, kindAlertComposer,
                    kindDocumentClient, documentParser, quoteClient,
                    new SeenStore(Path.of("seen_kind.txt")), disclosureKeys, config, marketCalendar);
            if (autoTrader != null) kindPollerService.setTradeSignalListener(autoTrader);   // KIND 경로 훅
            kindPollerService.start();
        } else {
            kindPollerService = null;
            log.info("KIND 폴링 비활성화 (KIND_ENABLED=false)");
        }

        // 뉴스 헤드라인 버퍼 — 뉴스 폴러가 신규 기사를 쌓고, 시황 리포트가 지난 1시간치를 읽어 분석 재료로 쓴다.
        // (개별 속보 발송을 대체 — 정치·뻔한 지수 뉴스 대신 시간당 시황 리포트에 뉴스 맥락을 녹인다.)
        NewsHeadlineBuffer newsHeadlineBuffer = new NewsHeadlineBuffer();

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
                kisNotifier.send(config.kisGainerAlertEnabled()
                        ? "🚨 급등 알림이 시작되었습니다."
                        : "📊 외국인·기관 수급 랭킹 알림이 시작되었습니다.");
                log.info("KIS 알림 채널 분리 (channel/room: {})", kisChannelId);
            }
            // 장 흐름 분석 리포트용 LLM — LLM_PROVIDER로 선택(gemini: 클라우드·무료한도·렉없음 / ollama: 로컬).
            // MARKET_REPORT_ENABLED=false면 KisPollerService가 무시한다(불필요한 호출 방지).
            LlmClient llm = "ollama".equals(config.llmProvider())
                    ? new OllamaClient(config.ollamaBaseUrl(), config.ollamaModel())
                    : new GeminiClient(config.geminiApiKey(), config.geminiModel());
            kisPollerService = new KisPollerService(
                    kisClient,   // 주가추적과 공유 — 토큰 분당 1회 발급 한도 때문에 인스턴스를 나누지 않는다.
                    kisNotifier,     // 표·급등·섹터·수급 랭킹 → KIS 채널
                    newsNotifier,    // 시황 매크로 분석(LLM) → 뉴스 채널
                    new KisAlertComposer(), config, Path.of("kis_sectors.txt"), llm, marketCalendar,
                    newsHeadlineBuffer);   // 지난 1시간 뉴스 헤드라인을 분석 재료로 읽는다
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
            // 뉴스 링크는 오래되면 재등장하지 않으므로 접수번호 계열보다 작은 상한으로 유지한다.
            SeenStore newsSeenStore = new SeenStore(Path.of("seen_news.txt"), 50_000);
            newsPollerService = new NewsPollerService(
                    new RssClient(), RssFeed.parseList(config.newsRssFeeds()),
                    GoogleNewsFeeds.of(config.newsGoogleKeywords()),
                    newsClient, newsHeadlineBuffer, newsSeenStore, config);
            newsPollerService.start();
        } else {
            newsPollerService = null;
            log.info("뉴스 폴링 비활성화 (NEWS_ENABLED=false)");
        }

        // 람다 캡처용 final 참조 (kisNotifier는 위에서 재할당될 수 있어 effectively final이 아님).
        final Notifier kisNotifierRef = kisNotifier;
        final boolean separateKisChannelRef = separateKisChannel;
        final AutoTradeService autoTraderRef = autoTrader;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("종료 신호 수신, 서비스 중지 중...");
            pollerService.stop();
            priceTracker.stop();
            if (kindPollerService != null) kindPollerService.stop();
            if (kisPollerService != null) kisPollerService.stop();
            if (newsPollerService != null) newsPollerService.stop();
            if (autoTraderRef != null) autoTraderRef.stop();
            notifier.stop();
            if (newsNotifier != notifier) newsNotifier.stop();
            if (separateKisChannelRef) kisNotifierRef.stop();
            log.info("봇 종료 완료");
        }));
    }

    /**
     * 거래일 캘린더를 만든다 — 휴장일 캐시 파일(krx_holidays.txt)을 읽고, KIS가 있으면 국내휴장일 조회로
     * 실측 휴장일을 받아 캐시를 최신화한다. 조회 실패·KIS 미설정이면 캐시 파일만(없으면 주말만 거른다).
     */
    private static MarketCalendar buildMarketCalendar(KisClient kisClient) {
        Path holidayFile = Path.of("krx_holidays.txt");
        Set<LocalDate> holidays = new TreeSet<>(MarketCalendar.loadFile(holidayFile));
        if (kisClient != null) {
            List<LocalDate> fetched = kisClient.marketClosedDays(LocalDate.now(ZoneId.of("Asia/Seoul")));
            if (!fetched.isEmpty()) {
                holidays.addAll(fetched);
                MarketCalendar.saveFile(holidayFile, holidays);   // 실측 성공 시에만 캐시 갱신(빈 조회로 덮어쓰지 않음)
            }
        }
        log.info("거래일 캘린더 준비 — 휴장일 {}건 (주말은 항상 제외)", holidays.size());
        return new MarketCalendar(holidays);
    }

    /**
     * 인스턴스가 하나만 뜨도록 파일 락을 건다. 이미 다른 프로세스가 잡고 있으면 즉시 종료한다.
     * 두 인스턴스가 동시에 돌면 각자 메모리 중복필터(SeenStore)와 뉴스 버퍼를 따로 들고 있어
     * 서로의 알림을 못 막고, 같은 기사를 인스턴스 수만큼 중복 반영하기 때문이다.
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
