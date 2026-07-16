package com.example.dart.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;

/**
 * 앱 전체 설정 — 컨텍스트별 중첩 record로 나눠, 어느 컨텍스트가 어떤 설정을 쓰는지 타입으로 드러낸다.
 * 각 서비스는 {@code AppConfig} 전체가 아니라 자기 서브 설정만 생성자로 받는다(God config 방지).
 * 로드·검증 진입점은 {@link #load()} 하나로 유지한다(.env + 시스템 환경변수, env 우선).
 */
public record AppConfig(
        DartConfig dart,
        NotifyConfig notification,
        NewsConfig news,
        KindConfig kind,
        KisConfig kis,
        LlmConfig llm,
        TradeConfig trade
) {

    /** DART 공시 폴링 + 공시 제목 필터 설정. */
    public record DartConfig(
            String apiKey,
            int pollIntervalSec,
            String corpCls,
            String pblntfTy,
            List<String> filterExtraKeywords,
            List<String> filterExcludeKeywords) {}

    /** 알림 채널(Discord/Webex) 설정 — 공시 기본 채널 + 뉴스/KIS 분리 채널(선택). */
    public record NotifyConfig(
            String notifier,
            String webexBotToken,
            String webexRoomId,
            String webexNewsRoomId,
            String webexKisRoomId,
            String discordBotToken,
            String discordChannelId,
            String discordNewsChannelId,
            String discordKisChannelId) {

        /** 뉴스 전용 채널/룸 ID. 미설정이면 null — 공시와 같은 채널 사용. */
        public String newsChannelId() {
            String id = switch (notifier) {
                case "discord" -> discordNewsChannelId;
                case "webex"   -> webexNewsRoomId;
                default        -> null;
            };
            return (id == null || id.isBlank()) ? null : id;
        }

        /** KIS 변동성 전용 채널/룸 ID. 미설정이면 null — 공시와 같은 채널 사용. */
        public String kisChannelId() {
            String id = switch (notifier) {
                case "discord" -> discordKisChannelId;
                case "webex"   -> webexKisRoomId;
                default        -> null;
            };
            return (id == null || id.isBlank()) ? null : id;
        }
    }

    /** 뉴스 수집(RSS/구글/네이버) 설정 — 수집만 하고 발송은 시황 리포트가 소비한다. */
    public record NewsConfig(
            boolean enabled,
            String naverClientId,
            String naverClientSecret,
            int pollIntervalSec,
            int rssPollIntervalSec,
            List<String> keywords,
            List<String> badKeywords,
            List<String> macroKeywords,
            List<String> macroTopics,
            List<String> macroTriggers,
            List<String> flipKeywords,
            List<String> excludeKeywords,
            List<String> breakingKeywords,
            List<String> rssFeeds,
            int maxAgeMin,
            int macroCooldownMin,
            List<String> googleKeywords,
            int googlePollIntervalSec) {

        /**
         * 네이버 검색에 쓰는 전체 키워드 (호재 + 악재 + 시황 단독).
         * 시황 주제어(미국, 트럼프 등)는 검색어로 쓰면 일상 기사가 쏟아지므로 제외 —
         * 주제어+충격어 조합 이슈는 RSS 폴링이 잡는다.
         */
        public List<String> allKeywords() {
            List<String> all = new java.util.ArrayList<>(keywords);
            all.addAll(badKeywords);
            all.addAll(macroKeywords);
            return all;
        }

        /** 네이버 검색 보완망 사용 여부 — 키 설정 시에만. */
        public boolean naverEnabled() {
            return naverClientId != null && !naverClientId.isBlank();
        }
    }

    /** KIND(거래소 공시) 선행 폴링 설정. corpCls는 DART와 같은 CORP_CLS 값을 공유한다. */
    public record KindConfig(
            boolean enabled,
            int pollIntervalSec,
            String corpCls) {}

    /** KIS 시장분석(급등·수급·섹터·시황 리포트) 설정. */
    public record KisConfig(
            String appKey,
            String appSecret,
            int pollIntervalSec,
            double minChangePct,
            int cooldownMin,
            String marketDivCode,
            int sectorSummaryMin,
            int investorFlowMin,
            int marketFlowMin,
            boolean gainerAlertEnabled,
            boolean paper,
            boolean marketReportEnabled,
            int marketReportIntervalMin,
            boolean marketReportGrounding) {

        /** KIS 변동성 정찰 사용 여부 — 앱키·시크릿이 모두 설정된 경우에만 (별도 플래그 불필요). */
        public boolean enabled() {
            return appKey != null && !appKey.isBlank()
                    && appSecret != null && !appSecret.isBlank();
        }
    }

    /** 시황 분석용 LLM(Gemini/Ollama) 설정. */
    public record LlmConfig(
            String provider,
            String geminiApiKey,
            String geminiModel,
            String ollamaBaseUrl,
            String ollamaModel) {}

    /** 공시 기반 자동매매(Stage 1: 드라이런) 설정. */
    public record TradeConfig(
            boolean enabled,
            String mode,
            long budgetWon,
            double minSalesRatio,
            double stopLossPct,
            double takeProfitPct,
            int maxPositions,
            int monitorSec,
            String eodClose) {}

    /**
     * 재현율 우선 — 이슈가 될 "가능성"이 있는 표현은 최대한 넓게 잡는다.
     * 넓은 키워드("임상", "특허")의 부정 문맥은 반전어(NEWS_FLIP_KEYWORDS)가 악재로 돌린다.
     */
    private static final String DEFAULT_NEWS_KEYWORDS =
            "수주,공급계약,납품,우선협상대상자,기술수출,기술이전,라이선스,라이센스,"
            + "FDA,EMA,임상,신약,품목허가,희귀의약품,"
            + "무상증자,자사주,주식 소각,특별배당,특허,"
            + "흑자전환,어닝서프라이즈,최대 실적,실적 호조,상한가,신고가,"
            + "M&A,지분 인수,경영권 인수,증설,신사업,세계 최초,국내 최초,수혜,MOU,국책과제";

    private static final String DEFAULT_NEWS_BAD_KEYWORDS =
            "하한가,거래정지,상장폐지,압수수색,횡령,배임,유상증자,감자,파산,회생절차,부도,리콜,불성실공시,"
            + "어닝쇼크,적자전환,영업정지,관리종목,분식회계,피소,패소,"
            + "소송,적자,손실,화재,폭발,해킹,구속,기소";

    /** 단독으로 알림이 나가는 시황 키워드 — 그 자체로 시장 이벤트인 것만. */
    private static final String DEFAULT_NEWS_MACRO_KEYWORDS =
            "FOMC,기준금리,서킷브레이커,사이드카,비상계엄,호르무즈";

    /**
     * 시황 주제어 — 일상 기사에도 흔한 단어라 단독으로는 알리지 않고,
     * 충격어(트리거)와 같은 제목에 있을 때만 시황 뉴스로 잡는다.
     */
    private static final String DEFAULT_NEWS_MACRO_TOPICS =
            "유가,미국,이란,트럼프,중동,이스라엘,환율,원달러,중국,연준,코스피,나스닥,반도체,국제유가";

    private static final String DEFAULT_NEWS_MACRO_TRIGGERS =
            "급등,급락,폭등,폭락,공습,폭격,공격,전쟁,참전,보복,제재,봉쇄,관세,합의,타결,결렬,휴전,"
            + "긴급,비상사태,충격,미사일,인상,인하,핵실험,핵시설,핵합의";

    /**
     * 호재↔악재 반전어 — "유상증자 철회"는 호재, "수주 취소"는 악재,
     * "소송 승소"·"적자 끊고 흑자"는 호재. 매칭 키워드 자신은 반전어 검색에서 제외된다.
     */
    private static final String DEFAULT_NEWS_FLIP_KEYWORDS =
            "철회,취소,무산,불발,실패,중단,보류,연기,부결,불승인,해제,모면,무혐의,반환,"
            + "승소,무죄,해소,흑자,무효";

    /**
     * 속보 말머리 키워드 — 제목이 이 중 하나를 괄호류([]·<>·()·【】)로 감싸 달면 알림.
     * 매체마다 표기가 달라(속보·긴급·특보…) 설정으로 조정한다. 보도 차수([1보]·[2보]…)는
     * 코드에서 자동 인식한다. 종합·단독·특종은 속보성이 아니라 기본에서 뺐다 — 필요하면 추가.
     */
    private static final String DEFAULT_NEWS_BREAKING_KEYWORDS =
            "속보,긴급,특보,플래시,브레이킹";

    /**
     * 검증된 언론사 속보 RSS — "이름|URL" 콤마 구분. 포털 색인 없이 발행 즉시 잡힌다.
     * korea.kr 보도자료(정책브리핑·식약처·방위사업청)는 정책 수혜·품목허가·방산 수주의
     * 원천 신호 — 제목이 호재 키워드에 걸릴 때만 알림이 나가므로 노이즈는 키워드 게이트가 막는다.
     */
    private static final String DEFAULT_NEWS_RSS_FEEDS = String.join(",",
            "한국경제|https://www.hankyung.com/feed/finance",
            "이데일리|http://rss.edaily.co.kr/stock_news.xml",
            "머니투데이|http://rss.mt.co.kr/mt_news.xml",
            "연합인포맥스|https://news.einfomax.co.kr/rss/allArticle.xml",
            "연합뉴스|https://www.yna.co.kr/rss/economy.xml",
            "파이낸셜뉴스|https://www.fnnews.com/rss/r20/fn_realnews_stock.xml",
            "매일경제|https://www.mk.co.kr/rss/30800011/",
            "조선비즈|https://biz.chosun.com/arc/outboundfeeds/rss/category/stock/?outputType=xml",
            "전자신문|https://rss.etnews.com/Section902.xml",
            "아시아경제|https://www.asiae.co.kr/rss/stock.htm",
            "서울경제|https://www.sedaily.com/rss/finance",
            "이투데이|https://rss.etoday.co.kr/eto/market_news.xml",
            "뉴스핌|http://rss.newspim.com/news/category/105",
            "인포스탁데일리|https://www.infostockdaily.co.kr/rss/allArticle.xml",
            "정책브리핑|https://www.korea.kr/rss/pressrelease.xml",
            "식약처|https://www.korea.kr/rss/dept_mfds.xml",
            "방위사업청|https://www.korea.kr/rss/dept_dapa.xml");

    /**
     * 구글뉴스 검색 RSS용 키워드 — 네이버 API 사각지대(중소매체·외신 한글판) 보완망.
     * 검색어 하나가 피드 하나가 되므로 가치 큰 정밀 쿼리만. 호출량 = 키워드 수 × (86400/주기).
     */
    private static final String DEFAULT_NEWS_GOOGLE_KEYWORDS =
            "수주,공급계약,기술수출,품목허가,FDA 승인,무상증자,자사주 소각,흑자전환,우선협상대상자,임상 3상";

    /**
     * .env + 시스템 환경변수(env 우선)에서 설정을 읽어 컨텍스트별 서브 record로 조립한다.
     * 각 {@code loadXxx}가 자기 record만 책임지고, 조립 후 {@link #validate}가 필수·상호 제약을 검증한다.
     */
    public static AppConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        DartConfig dart = loadDart(dotenv);
        NotifyConfig notify = loadNotify(dotenv);
        NewsConfig news = loadNews(dotenv);
        // KIND는 DART와 같은 CORP_CLS를 공유한다 — 같은 공시가 교차 중복 제거로 매칭되려면 시장 코드가 일치해야 한다.
        KindConfig kind = loadKind(dotenv, dart.corpCls());
        KisConfig kis = loadKis(dotenv);
        LlmConfig llm = loadLlm(dotenv);
        TradeConfig trade = loadTrade(dotenv);

        validate(dart, notify, news, kis);

        return new AppConfig(dart, notify, news, kind, kis, llm, trade);
    }

    private static DartConfig loadDart(Dotenv dotenv) {
        int pollInterval = Integer.parseInt(resolveOrDefault(dotenv, "POLL_INTERVAL_SEC", "7"));
        String corpCls  = resolveOrDefault(dotenv, "CORP_CLS",   "Y,K");
        // B=주요사항보고(공급계약·자사주취득 등), I=거래소공시(수주 등)
        String pblntfTy = resolveOrDefault(dotenv, "PBLNTF_TY",  "B,I");
        return new DartConfig(
                resolve(dotenv, "DART_API_KEY"), pollInterval, corpCls, pblntfTy,
                parseCsv(resolve(dotenv, "FILTER_EXTRA_KEYWORDS")),
                parseCsv(resolve(dotenv, "FILTER_EXCLUDE_KEYWORDS")));
    }

    private static NotifyConfig loadNotify(Dotenv dotenv) {
        String notifier = resolveOrDefault(dotenv, "NOTIFIER", "webex").toLowerCase();
        return new NotifyConfig(notifier,
                resolve(dotenv, "WEBEX_BOT_TOKEN"),
                resolve(dotenv, "WEBEX_ROOM_ID"),
                resolve(dotenv, "WEBEX_NEWS_ROOM_ID"),
                // KIS 변동성 전용 채널(선택) — 미설정이면 공시 채널 공유. 뉴스 채널 분리와 동일 패턴.
                resolve(dotenv, "WEBEX_KIS_ROOM_ID"),
                resolve(dotenv, "DISCORD_BOT_TOKEN"),
                resolve(dotenv, "DISCORD_CHANNEL_ID"),
                resolve(dotenv, "DISCORD_NEWS_CHANNEL_ID"),
                resolve(dotenv, "DISCORD_KIS_CHANNEL_ID"));
    }

    private static NewsConfig loadNews(Dotenv dotenv) {
        boolean newsEnabled = Boolean.parseBoolean(resolveOrDefault(dotenv, "NEWS_ENABLED", "true"));
        // 기본 240초 — 키워드 ~60개 × 360회 = 일 21,600회로 네이버 한도(25,000회) 안
        int newsPollInterval    = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_POLL_INTERVAL_SEC", "240"));
        int newsRssPollInterval = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_RSS_POLL_INTERVAL_SEC", "30"));
        int newsMaxAgeMin = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_MAX_AGE_MIN", "30"));
        int newsMacroCooldownMin = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_MACRO_COOLDOWN_MIN", "10"));
        // 기본 120초 — 키워드 10개 × 720회 = 일 7,200회. 구글 비공식 한도라 429 발생 시 주기를 늘린다.
        int newsGooglePollInterval = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_GOOGLE_POLL_INTERVAL_SEC", "120"));
        return new NewsConfig(newsEnabled,
                resolve(dotenv, "NAVER_CLIENT_ID"),
                resolve(dotenv, "NAVER_CLIENT_SECRET"),
                newsPollInterval, newsRssPollInterval,
                parseCsv(resolveOrDefault(dotenv, "NEWS_KEYWORDS", DEFAULT_NEWS_KEYWORDS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_BAD_KEYWORDS", DEFAULT_NEWS_BAD_KEYWORDS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_KEYWORDS", DEFAULT_NEWS_MACRO_KEYWORDS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_TOPICS", DEFAULT_NEWS_MACRO_TOPICS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_TRIGGERS", DEFAULT_NEWS_MACRO_TRIGGERS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_FLIP_KEYWORDS", DEFAULT_NEWS_FLIP_KEYWORDS)),
                parseCsv(resolve(dotenv, "NEWS_EXCLUDE_KEYWORDS")),
                parseCsv(resolveOrDefault(dotenv, "NEWS_BREAKING_KEYWORDS", DEFAULT_NEWS_BREAKING_KEYWORDS)),
                parseCsv(resolveOrDefault(dotenv, "NEWS_RSS_FEEDS", DEFAULT_NEWS_RSS_FEEDS)),
                newsMaxAgeMin, newsMacroCooldownMin,
                parseCsv(resolveOrDefault(dotenv, "NEWS_GOOGLE_KEYWORDS", DEFAULT_NEWS_GOOGLE_KEYWORDS)),
                newsGooglePollInterval);
    }

    /** KIND(거래소 공시)는 DART보다 선게시가 빈번 — 같은 공시는 교차 중복 제거로 한쪽만 알린다. corpCls는 DART와 공유. */
    private static KindConfig loadKind(Dotenv dotenv, String corpCls) {
        boolean kindEnabled = Boolean.parseBoolean(resolveOrDefault(dotenv, "KIND_ENABLED", "true"));
        int kindPollIntervalSec = Integer.parseInt(resolveOrDefault(dotenv, "KIND_POLL_INTERVAL_SEC", "15"));
        return new KindConfig(kindEnabled, kindPollIntervalSec, corpCls);
    }

    private static KisConfig loadKis(Dotenv dotenv) {
        // KIS(한국투자증권) 변동성 급등 정찰 — 키는 .env가 아닌 시스템 환경변수로 주입(resolve가 env 우선).
        // 앱키·시크릿이 있으면 자동 활성(네이버와 동일 패턴) — 별도 ENABLED 플래그 불필요.
        String kisAppKey    = resolve(dotenv, "KIS_APP_KEY");
        String kisAppSecret = resolve(dotenv, "KIS_APP_SECRET");
        int kisPollIntervalSec    = Integer.parseInt(resolveOrDefault(dotenv, "KIS_POLL_INTERVAL_SEC", "60"));
        // 전일 종가 대비 등락률 하한 — 이 % 이상 오른 종목만 알린다(거래량·거래대금·주가 조건 없음).
        double kisMinChangePct    = Double.parseDouble(resolveOrDefault(dotenv, "KIS_MIN_CHANGE_PCT", "10"));    // 등락률 +10%↑
        int kisCooldownMin        = Integer.parseInt(resolveOrDefault(dotenv, "KIS_COOLDOWN_MIN", "60"));        // 같은 종목 재알림 간격
        // 등락률순위 조회 시장구분 — J: KRX만, NX: NXT(넥스트레이드)만, UN: 통합(KRX+NXT 합산).
        // 기본 J(KRX). UN/NX는 NXT 거래·연장세션까지 잡지만, 이 등락률순위 TR이 계좌에서 UN을 거부하면
        // (rt_cd=2 "INVALID FID_COND_MRKT_DIV_CODE") 알림이 안 나간다 — KIS API에 NXT 데이터 사용이 열린
        // 계좌에서만 UN/NX로 설정한다. UN/NX 설정 시 폴러가 NXT 거래시간(08:00~20:00)으로 자동 확장된다.
        String kisMarketDivCode   = resolveOrDefault(dotenv, "KIS_MARKET_DIV_CODE", "J").trim().toUpperCase();
        // 급등 종목들의 KRX 업종을 집계해 N분마다 섹터 요약을 보낸다. 0이면 비활성. 기본 10분.
        int kisSectorSummaryMin   = Integer.parseInt(resolveOrDefault(dotenv, "KIS_SECTOR_SUMMARY_MIN", "10"));
        // 장중 외국인·기관 순매수/순매도 상위 종목(가집계)을 N분마다 보낸다. 0이면 비활성. 기본 10분.
        int kisInvestorFlowMin    = Integer.parseInt(resolveOrDefault(dotenv, "KIS_INVESTOR_FLOW_MIN", "10"));
        // 코스피·코스닥 '시장 전체' 외국인·기관·개인 순매수 헤드라인을 N분마다 보낸다(네이버 소스). 0이면 비활성. 기본 10분.
        int kisMarketFlowMin      = Integer.parseInt(resolveOrDefault(dotenv, "KIS_MARKET_FLOW_MIN", "10"));
        // 급등(개별 종목) 알림 활성 여부. false면 급등 알림을 끈다(수급 랭킹만 운용할 때). 기본 true.
        boolean kisGainerAlertEnabled = Boolean.parseBoolean(resolveOrDefault(dotenv, "KIS_GAINER_ALERT_ENABLED", "true"));
        // 모의투자(paper) 앱키 여부. true면 모의 도메인(openapivts:29443)을 쓴다 — 모의 앱키는 실전 도메인에서
        // 시세조회(inquire-price)가 EGW02004로 거부돼 업종 분류가 전부 '미분류'가 되므로 키 종류에 맞춰야 한다.
        boolean kisPaper          = Boolean.parseBoolean(resolveOrDefault(dotenv, "KIS_PAPER", "false"));
        // 장 흐름 분석 리포트 — KIS 거래대금·급등 데이터를 모아 LLM(Gemini/Ollama)으로 한국어 요약을 만들어
        // N분마다 보낸다. KIS가 활성이고 LLM 키가 있을 때만 의미가 있다. 기본 활성(true) — 끄려면
        // 환경변수/.env에 MARKET_REPORT_ENABLED=false 를 명시한다. (키 없으면 호출이 실패해도 안전하게 건너뜀)
        boolean marketReportEnabled  = Boolean.parseBoolean(resolveOrDefault(dotenv, "MARKET_REPORT_ENABLED", "true"));
        int marketReportIntervalMin  = Integer.parseInt(resolveOrDefault(dotenv, "MARKET_REPORT_INTERVAL_MIN", "60"));
        // 시황 분석에 실시간 검색 그라운딩(Gemini google_search) 사용 여부. 기본 true.
        // 그라운딩은 Gemini 2.5에서 검색 호출당 과금 — 끄면(false) 평문 분석(검색 없이 국내 실측만).
        boolean marketReportGrounding = Boolean.parseBoolean(resolveOrDefault(dotenv, "MARKET_REPORT_GROUNDING", "true"));
        return new KisConfig(kisAppKey, kisAppSecret, kisPollIntervalSec, kisMinChangePct, kisCooldownMin,
                kisMarketDivCode, kisSectorSummaryMin, kisInvestorFlowMin, kisMarketFlowMin,
                kisGainerAlertEnabled, kisPaper,
                marketReportEnabled, marketReportIntervalMin, marketReportGrounding);
    }

    private static LlmConfig loadLlm(Dotenv dotenv) {
        // 요약 생성 공급자 — gemini(클라우드, 무료한도·렉없음) 또는 ollama(로컬, 무료·PC부하).
        String llmProvider   = resolveOrDefault(dotenv, "LLM_PROVIDER", "gemini").trim().toLowerCase();
        // Gemini(Google AI Studio) — 키는 시스템 환경변수/.env로 주입(env 우선). 모델은 무료 flash 계열 기본.
        String geminiModel   = resolveOrDefault(dotenv, "GEMINI_MODEL", "gemini-2.5-flash");
        // 로컬 Ollama 주소·모델. 키 아님(로컬 무인증). LLM_PROVIDER=ollama일 때만 사용.
        String ollamaBaseUrl = resolveOrDefault(dotenv, "OLLAMA_BASE_URL", "http://localhost:11434");
        String ollamaModel   = resolveOrDefault(dotenv, "OLLAMA_MODEL", "exaone3.5:7.8b");
        return new LlmConfig(llmProvider, resolve(dotenv, "GEMINI_API_KEY"), geminiModel, ollamaBaseUrl, ollamaModel);
    }

    private static TradeConfig loadTrade(Dotenv dotenv) {
        // 공시 기반 자동매매 — 기본 비활성. Stage1은 드라이런(실주문 없음)만 구현. 실제 자금 보호를 위해 명시 설정 필요.
        boolean autoTradeEnabled     = Boolean.parseBoolean(resolveOrDefault(dotenv, "AUTO_TRADE_ENABLED", "false"));
        String autoTradeMode         = resolveOrDefault(dotenv, "AUTO_TRADE_MODE", "dryrun").trim().toLowerCase();
        long autoTradeBudgetWon      = Long.parseLong(resolveOrDefault(dotenv, "AUTO_TRADE_BUDGET_WON", "1000000"));
        double autoTradeMinSalesRatio= Double.parseDouble(resolveOrDefault(dotenv, "AUTO_TRADE_MIN_SALES_RATIO", "50"));
        double autoTradeStopLossPct  = Double.parseDouble(resolveOrDefault(dotenv, "AUTO_TRADE_STOP_LOSS_PCT", "2"));
        double autoTradeTakeProfitPct= Double.parseDouble(resolveOrDefault(dotenv, "AUTO_TRADE_TAKE_PROFIT_PCT", "5"));
        int autoTradeMaxPositions    = Integer.parseInt(resolveOrDefault(dotenv, "AUTO_TRADE_MAX_POSITIONS", "5"));
        int autoTradeMonitorSec      = Integer.parseInt(resolveOrDefault(dotenv, "AUTO_TRADE_MONITOR_SEC", "30"));
        String autoTradeEodClose     = resolveOrDefault(dotenv, "AUTO_TRADE_EOD_CLOSE", "15:20").trim();
        return new TradeConfig(autoTradeEnabled, autoTradeMode, autoTradeBudgetWon, autoTradeMinSalesRatio,
                autoTradeStopLossPct, autoTradeTakeProfitPct, autoTradeMaxPositions,
                autoTradeMonitorSec, autoTradeEodClose);
    }

    /** 필수값·선택자별 조건부 필수·키 쌍 짝맞춤을 검증한다. 문제가 있으면 즉시 {@link IllegalStateException}. */
    private static void validate(DartConfig dart, NotifyConfig notify, NewsConfig news, KisConfig kis) {
        // 공통 필수
        if (dart.apiKey() == null || dart.apiKey().isBlank()) {
            throw new IllegalStateException("DART_API_KEY 환경변수가 설정되지 않았습니다.");
        }

        // 알림 선택자별 조건부 필수 검증
        switch (notify.notifier()) {
            case "webex" -> {
                if (notify.webexBotToken() == null || notify.webexBotToken().isBlank())
                    throw new IllegalStateException("WEBEX_BOT_TOKEN 환경변수가 설정되지 않았습니다.");
                if (notify.webexRoomId() == null || notify.webexRoomId().isBlank())
                    throw new IllegalStateException("WEBEX_ROOM_ID 환경변수가 설정되지 않았습니다.");
            }
            case "discord" -> {
                if (notify.discordBotToken() == null || notify.discordBotToken().isBlank())
                    throw new IllegalStateException("DISCORD_BOT_TOKEN 환경변수가 설정되지 않았습니다.");
                if (notify.discordChannelId() == null || notify.discordChannelId().isBlank())
                    throw new IllegalStateException("DISCORD_CHANNEL_ID 환경변수가 설정되지 않았습니다.");
            }
            default -> throw new IllegalStateException(
                    "알 수 없는 NOTIFIER 값: \"" + notify.notifier() + "\". webex 또는 discord 중 하나를 지정하세요.");
        }

        // 네이버 키: 하나만 있으면 설정 실수이므로 즉시 실패. (RSS는 키 없이 동작)
        boolean hasId     = news.naverClientId() != null && !news.naverClientId().isBlank();
        boolean hasSecret = news.naverClientSecret() != null && !news.naverClientSecret().isBlank();
        if (hasId != hasSecret) {
            throw new IllegalStateException(
                    "NAVER_CLIENT_ID와 NAVER_CLIENT_SECRET은 둘 다 설정하거나 둘 다 비워야 합니다.");
        }
        if (news.enabled()
                && news.keywords().isEmpty() && news.badKeywords().isEmpty() && news.macroKeywords().isEmpty()) {
            throw new IllegalStateException("뉴스 폴링이 활성화됐지만 키워드가 모두 비어 있습니다.");
        }

        // KIS 키: 하나만 있으면 설정 실수이므로 즉시 실패. 둘 다 있으면 자동 활성, 둘 다 없으면 비활성.
        boolean hasKisKey    = kis.appKey() != null && !kis.appKey().isBlank();
        boolean hasKisSecret = kis.appSecret() != null && !kis.appSecret().isBlank();
        if (hasKisKey != hasKisSecret) {
            throw new IllegalStateException(
                    "KIS_APP_KEY와 KIS_APP_SECRET은 둘 다 설정하거나 둘 다 비워야 합니다.");
        }
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String resolve(Dotenv dotenv, String key) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) return val;
        return dotenv.get(key);
    }

    private static String resolveOrDefault(Dotenv dotenv, String key, String defaultVal) {
        String val = resolve(dotenv, key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
