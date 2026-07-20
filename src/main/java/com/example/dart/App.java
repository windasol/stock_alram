package com.example.dart;

import com.example.dart.config.AppConfig;
import com.example.dart.disclosure.infra.DartClient;
import com.example.dart.disclosure.domain.NewsFilter;
import com.example.dart.disclosure.application.KindAlertComposer;
import com.example.dart.disclosure.infra.KindClient;
import com.example.dart.disclosure.infra.KindDocumentClient;
import com.example.dart.disclosure.application.KindPollerService;
import com.example.dart.kis.application.KisAlertComposer;
import com.example.dart.kis.application.KisPollerService;
import com.example.dart.kis.infra.DomesticMarketClient;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.kis.infra.UsFuturesClient;
import com.example.dart.llm.GeminiClient;
import com.example.dart.llm.LlmClient;
import com.example.dart.llm.OllamaClient;
import com.example.dart.news.GoogleNewsFeeds;
import com.example.dart.news.NaverNewsClient;
import com.example.dart.news.NewsHeadlineBuffer;
import com.example.dart.news.NewsPollerService;
import com.example.dart.news.RssClient;
import com.example.dart.news.RssFeed;
import com.example.dart.disclosure.application.DisclosureEnricher;
import com.example.dart.econcal.application.EconCalendarPoller;
import com.example.dart.econcal.infra.FinnhubClient;
import com.example.dart.econcal.infra.FredClient;
import com.example.dart.notify.DiscordService;
import com.example.dart.notify.Notifier;
import com.example.dart.notify.WebexService;
import com.example.dart.disclosure.infra.DocumentParser;
import com.example.dart.common.infra.StockQuoteClient;
import com.example.dart.pricetrack.application.DisclosurePriceTracker;
import com.example.dart.disclosure.application.DocumentService;
import com.example.dart.disclosure.application.PollerService;
import com.example.dart.trade.AutoTradeService;
import com.example.dart.common.domain.KstTime;
import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.common.infra.SeenStore;
import com.example.dart.common.infra.SingleInstanceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 조립 루트 — 의존성 생성·연결과 생명주기 관리만 담당한다. */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("DART 실시간 호재 알림 봇 시작");
        SingleInstanceLock.acquire(Path.of("app.lock"));

        AppConfig config = AppConfig.load();
        DartClient dartClient = new DartClient(config.dart().apiKey());
        NewsFilter newsFilter = new NewsFilter(config.dart().filterExtraKeywords(), config.dart().filterExcludeKeywords());
        SeenStore seenStore = new SeenStore(Path.of("seen.txt"));
        DocumentParser documentParser = new DocumentParser();
        DocumentService documentService = new DocumentService(dartClient, documentParser);
        StockQuoteClient quoteClient = new StockQuoteClient();
        // KIND 소스 — DART가 먼저 잡은 계약을 KIND 뷰어 본문으로 즉시 보강하는 빠른 경로에서 쓰고,
        // KIND 폴러에서도 같은 인스턴스를 재사용한다. KIND 비활성 시 null이면 빠른 경로는 즉시 폴백한다.
        KindClient kindClient = config.kind().enabled() ? new KindClient() : null;
        KindDocumentClient kindDocumentClient = config.kind().enabled() ? new KindDocumentClient() : null;
        DisclosureEnricher enricher = new DisclosureEnricher(documentService, newsFilter, quoteClient, dartClient,
                kindClient, kindDocumentClient, documentParser);

        Notifier notifier = createNotifier(config, null);

        // 뉴스 전용 채널이 설정되면 분리 — 공시(소량·확정)와 뉴스(다량·속보)를 섞지 않는다.
        String newsChannelId = config.notification().newsChannelId();
        Notifier newsNotifier = (newsChannelId != null)
                ? createNotifier(config, newsChannelId)
                : notifier;

        boolean separateNewsChannel = newsNotifier != notifier;

        notifier.start();
        // 뉴스 채널이 분리됐으면 공시 채널엔 공시만 언급한다.
        notifier.send(config.news().enabled() && !separateNewsChannel
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
        KisClient kisClient = config.kis().enabled()
                ? new KisClient(config.kis().appKey(), config.kis().appSecret(),
                        config.kis().marketDivCode(), config.kis().paper(), Path.of("kis_token.txt"))
                : null;

        // 거래일 캘린더 — 캐시 파일(krx_holidays.txt)의 휴장일을 읽고, KIS가 있으면 실측 휴장일을 조회해 최신화한다.
        // 공휴일 데이터가 비면 주말만 걸러 기존 동작으로 안전하게 폴백한다.
        MarketCalendar marketCalendar = buildMarketCalendar(kisClient);

        // 공시 후 주가 추적(통계) — 호재 공시 감지 시각 기준 10분간 주가 변동을 기록·요약한다.
        DisclosurePriceTracker priceTracker = new DisclosurePriceTracker(
                quoteClient, kisClient, notifier, Path.of("disclosure_price_stats.jsonl"), marketCalendar);

        // 공시 기반 자동매매(드라이런) — 계약 규모(매출 대비 ≥임계%) 신호를 두 공시 소스(DART DisclosureEnricher / KIND)에서
        // 받아 모의 매매한다. 현재가는 KIS 분봉으로 조회하므로 kisClient가 있어야 동작한다. 비활성/키 없음이면 리스너 null(무동작).
        KindAlertComposer kindAlertComposer = new KindAlertComposer();
        AutoTradeService autoTrader = null;
        if (config.trade().enabled() && kisClient != null) {
            autoTrader = new AutoTradeService(kisClient, notifier, marketCalendar, config.trade());
            enricher.setTradeSignalListener(autoTrader);   // DART 경로 훅(DisclosureEnricher.buildFollowup)
            autoTrader.start();
        } else if (config.trade().enabled()) {
            log.warn("자동매매 활성이지만 KIS 미설정 — 현재가 조회 불가로 비활성. KIS_APP_KEY/KIS_APP_SECRET 확인");
        }

        PollerService pollerService = new PollerService(
                dartClient, newsFilter, notifier, enricher,
                seenStore, disclosureKeys, config.dart(), priceTracker);
        pollerService.start();

        // KIND 폴러 — 거래소 공시는 KIND에 먼저 게시되는 경우가 많아 가장 빠른 공시 소스.
        KindPollerService kindPollerService;
        if (config.kind().enabled()) {
            kindPollerService = new KindPollerService(
                    kindClient, newsFilter, notifier, kindAlertComposer,
                    kindDocumentClient, documentParser, quoteClient,
                    new SeenStore(Path.of("seen_kind.txt")), disclosureKeys, config.kind(), marketCalendar);
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
        if (config.kis().enabled()) {
            String kisChannelId = config.notification().kisChannelId();
            if (kisChannelId != null) {
                kisNotifier = createNotifier(config, kisChannelId);
                separateKisChannel = true;
                kisNotifier.start();
                kisNotifier.send(config.kis().gainerAlertEnabled()
                        ? "🚨 급등 알림이 시작되었습니다."
                        : "📊 외국인·기관 수급 랭킹 알림이 시작되었습니다.");
                log.info("KIS 알림 채널 분리 (channel/room: {})", kisChannelId);
            }
            // 장 흐름 분석 리포트용 LLM — LLM_PROVIDER로 선택(gemini: 클라우드·무료한도·렉없음 / ollama: 로컬).
            // MARKET_REPORT_ENABLED=false면 KisPollerService가 무시한다(불필요한 호출 방지).
            LlmClient llm = "ollama".equals(config.llm().provider())
                    ? new OllamaClient(config.llm().ollamaBaseUrl(), config.llm().ollamaModel())
                    : new GeminiClient(config.llm().geminiApiKey(), config.llm().geminiModel());
            kisPollerService = new KisPollerService(
                    kisClient,   // 주가추적과 공유 — 토큰 분당 1회 발급 한도 때문에 인스턴스를 나누지 않는다.
                    kisNotifier,     // 표·급등·섹터·수급 랭킹 → KIS 채널
                    newsNotifier,    // 시황 매크로 분석(LLM) → 뉴스 채널
                    new KisAlertComposer(), config.kis(), Path.of("kis_sectors.txt"), llm, marketCalendar,
                    newsHeadlineBuffer,    // 지난 1시간 뉴스 헤드라인을 분석 재료로 읽는다
                    new UsFuturesClient(),       // 시황 리포트 '대외 여건' 소스
                    new DomesticMarketClient()); // 국내 지수·환율·시장 수급(네이버) 소스
            kisPollerService.start();
        } else {
            kisPollerService = null;
            log.info("KIS 변동성 폴링 비활성화 (KIS_APP_KEY/KIS_APP_SECRET 미설정)");
        }

        // 뉴스 폴러 — 공시 폴러와 별도 스레드에서 병렬 동작, Notifier만 공유.
        // RSS는 항상, 네이버 검색은 키가 있을 때만 보완망으로 동작.
        NewsPollerService newsPollerService;
        if (config.news().enabled()) {
            NaverNewsClient newsClient = config.news().naverEnabled()
                    ? new NaverNewsClient(config.news().naverClientId(), config.news().naverClientSecret())
                    : null;
            if (newsClient == null) {
                log.info("네이버 검색 보완망 비활성화 (NAVER_CLIENT_ID/SECRET 미설정) — RSS만 사용");
            }
            // 뉴스 링크는 오래되면 재등장하지 않으므로 접수번호 계열보다 작은 상한으로 유지한다.
            SeenStore newsSeenStore = new SeenStore(Path.of("seen_news.txt"), 50_000);
            newsPollerService = new NewsPollerService(
                    new RssClient(), RssFeed.parseList(config.news().rssFeeds()),
                    GoogleNewsFeeds.of(config.news().googleKeywords()),
                    newsClient, newsHeadlineBuffer, newsSeenStore, config.news());
            newsPollerService.start();
        } else {
            newsPollerService = null;
            log.info("뉴스 폴링 비활성화 (NEWS_ENABLED=false)");
        }

        // 경제/실적 캘린더 다이제스트 — 매일 아침 향후 N일의 미국 지표(FRED)·기업 실적(Finnhub)·FOMC를 한 번 발송.
        // 키가 하나라도 있으면 활성. 전용 채널이 설정되면 분리, 아니면 뉴스 채널(없으면 공시 채널)로 폴백.
        EconCalendarPoller econCalPoller = null;
        Notifier econCalNotifier = null;   // 전용 채널을 새로 연 경우에만 non-null(종료 시 stop 대상)
        if (config.econCalendar().enabled()) {
            FredClient fredClient = config.econCalendar().hasFred()
                    ? new FredClient(config.econCalendar().fredApiKey()) : null;
            FinnhubClient finnhubClient = config.econCalendar().hasFinnhub()
                    ? new FinnhubClient(config.econCalendar().finnhubApiKey()) : null;

            String econCalChannelId = config.notification().econCalChannelId();
            Notifier target;
            if (econCalChannelId != null) {
                econCalNotifier = createNotifier(config, econCalChannelId);
                econCalNotifier.start();
                econCalNotifier.send("📅 경제/실적 캘린더 알림이 시작되었습니다. (매일 아침 향후 일정 발송)");
                target = econCalNotifier;
                log.info("경제/실적 캘린더 채널 분리 (channel/room: {})", econCalChannelId);
            } else {
                target = newsNotifier;   // 뉴스 채널 공유(뉴스 채널이 없으면 그 자체가 공시 채널)
            }
            econCalPoller = new EconCalendarPoller(fredClient, finnhubClient, target, config.econCalendar());
            econCalPoller.start();
        } else {
            log.info("경제/실적 캘린더 다이제스트 비활성화 (FRED_API_KEY/FINNHUB_API_KEY 미설정)");
        }

        // 람다 캡처용 final 참조 (kisNotifier는 위에서 재할당될 수 있어 effectively final이 아님).
        final Notifier kisNotifierRef = kisNotifier;
        final EconCalendarPoller econCalPollerRef = econCalPoller;
        final Notifier econCalNotifierRef = econCalNotifier;
        final boolean separateKisChannelRef = separateKisChannel;
        final AutoTradeService autoTraderRef = autoTrader;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("종료 신호 수신, 서비스 중지 중...");
            pollerService.stop();
            priceTracker.stop();
            if (kindPollerService != null) kindPollerService.stop();
            if (kisPollerService != null) kisPollerService.stop();
            if (newsPollerService != null) newsPollerService.stop();
            if (econCalPollerRef != null) econCalPollerRef.stop();
            if (autoTraderRef != null) autoTraderRef.stop();
            notifier.stop();
            if (newsNotifier != notifier) newsNotifier.stop();
            if (separateKisChannelRef) kisNotifierRef.stop();
            if (econCalNotifierRef != null) econCalNotifierRef.stop();
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
            List<LocalDate> fetched = kisClient.marketClosedDays(LocalDate.now(KstTime.ZONE));
            if (!fetched.isEmpty()) {
                holidays.addAll(fetched);
                MarketCalendar.saveFile(holidayFile, holidays);   // 실측 성공 시에만 캐시 갱신(빈 조회로 덮어쓰지 않음)
            }
        }
        log.info("거래일 캘린더 준비 — 휴장일 {}건 (주말은 항상 제외)", holidays.size());
        return new MarketCalendar(holidays);
    }

    private static Notifier createNotifier(AppConfig config, String channelOverride) {
        AppConfig.NotifyConfig n = config.notification();
        return switch (n.notifier()) {
            case "discord" -> new DiscordService(n.discordBotToken(),
                    channelOverride != null ? channelOverride : n.discordChannelId());
            case "webex"   -> new WebexService(n.webexBotToken(),
                    channelOverride != null ? channelOverride : n.webexRoomId());
            default -> throw new IllegalStateException("알 수 없는 NOTIFIER: " + n.notifier());
        };
    }
}
