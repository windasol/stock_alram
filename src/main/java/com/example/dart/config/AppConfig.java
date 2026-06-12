package com.example.dart.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;

public record AppConfig(
        String dartApiKey,
        String notifier,
        String webexBotToken,
        String webexRoomId,
        String webexNewsRoomId,
        String discordBotToken,
        String discordChannelId,
        String discordNewsChannelId,
        int pollIntervalSec,
        String corpCls,
        String pblntfTy,
        List<String> filterExtraKeywords,
        List<String> filterExcludeKeywords,
        boolean newsEnabled,
        String naverClientId,
        String naverClientSecret,
        int newsPollIntervalSec,
        int newsRssPollIntervalSec,
        List<String> newsKeywords,
        List<String> newsBadKeywords,
        List<String> newsMacroKeywords,
        List<String> newsMacroTopics,
        List<String> newsMacroTriggers,
        List<String> newsFlipKeywords,
        List<String> newsExcludeKeywords,
        List<String> newsRssFeeds,
        int newsMaxAgeMin,
        int newsMacroCooldownMin
) {

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

    /** 검증된 언론사 속보 RSS — "이름|URL" 콤마 구분. 포털 색인 없이 발행 즉시 잡힌다. */
    private static final String DEFAULT_NEWS_RSS_FEEDS = String.join(",",
            "한국경제|https://www.hankyung.com/feed/finance",
            "이데일리|http://rss.edaily.co.kr/stock_news.xml",
            "머니투데이|http://rss.mt.co.kr/mt_news.xml",
            "연합인포맥스|https://news.einfomax.co.kr/rss/allArticle.xml",
            "연합뉴스|https://www.yna.co.kr/rss/economy.xml",
            "파이낸셜뉴스|https://www.fnnews.com/rss/r20/fn_realnews_stock.xml");

    /**
     * 네이버 검색에 쓰는 전체 키워드 (호재 + 악재 + 시황 단독).
     * 시황 주제어(미국, 트럼프 등)는 검색어로 쓰면 일상 기사가 쏟아지므로 제외 —
     * 주제어+충격어 조합 이슈는 RSS 폴링이 잡는다.
     */
    public List<String> allNewsKeywords() {
        List<String> all = new java.util.ArrayList<>(newsKeywords);
        all.addAll(newsBadKeywords);
        all.addAll(newsMacroKeywords);
        return all;
    }

    /** 네이버 검색 보완망 사용 여부 — 키 설정 시에만. */
    public boolean naverEnabled() {
        return naverClientId != null && !naverClientId.isBlank();
    }

    /** 뉴스 전용 채널/룸 ID. 미설정이면 null — 공시와 같은 채널 사용. */
    public String newsChannelId() {
        String id = switch (notifier) {
            case "discord" -> discordNewsChannelId;
            case "webex"   -> webexNewsRoomId;
            default        -> null;
        };
        return (id == null || id.isBlank()) ? null : id;
    }

    public static AppConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String dartApiKey      = resolve(dotenv, "DART_API_KEY");
        String notifier        = resolveOrDefault(dotenv, "NOTIFIER", "webex").toLowerCase();
        String webexBotToken   = resolve(dotenv, "WEBEX_BOT_TOKEN");
        String webexRoomId     = resolve(dotenv, "WEBEX_ROOM_ID");
        String webexNewsRoomId = resolve(dotenv, "WEBEX_NEWS_ROOM_ID");
        String discordBotToken = resolve(dotenv, "DISCORD_BOT_TOKEN");
        String discordChannelId = resolve(dotenv, "DISCORD_CHANNEL_ID");
        String discordNewsChannelId = resolve(dotenv, "DISCORD_NEWS_CHANNEL_ID");
        int pollInterval = Integer.parseInt(resolveOrDefault(dotenv, "POLL_INTERVAL_SEC", "7"));
        String corpCls  = resolveOrDefault(dotenv, "CORP_CLS",   "Y,K");
        // B=주요사항보고(공급계약·자사주취득 등), I=거래소공시(수주 등)
        String pblntfTy = resolveOrDefault(dotenv, "PBLNTF_TY",  "B,I");
        List<String> filterExtraKeywords   = parseCsv(resolve(dotenv, "FILTER_EXTRA_KEYWORDS"));
        List<String> filterExcludeKeywords = parseCsv(resolve(dotenv, "FILTER_EXCLUDE_KEYWORDS"));

        boolean newsEnabled = Boolean.parseBoolean(resolveOrDefault(dotenv, "NEWS_ENABLED", "true"));
        String naverClientId     = resolve(dotenv, "NAVER_CLIENT_ID");
        String naverClientSecret = resolve(dotenv, "NAVER_CLIENT_SECRET");
        // 기본 240초 — 키워드 ~60개 × 360회 = 일 21,600회로 네이버 한도(25,000회) 안
        int newsPollInterval    = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_POLL_INTERVAL_SEC", "240"));
        int newsRssPollInterval = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_RSS_POLL_INTERVAL_SEC", "30"));
        List<String> newsKeywords      = parseCsv(resolveOrDefault(dotenv, "NEWS_KEYWORDS", DEFAULT_NEWS_KEYWORDS));
        List<String> newsBadKeywords   = parseCsv(resolveOrDefault(dotenv, "NEWS_BAD_KEYWORDS", DEFAULT_NEWS_BAD_KEYWORDS));
        List<String> newsMacroKeywords = parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_KEYWORDS", DEFAULT_NEWS_MACRO_KEYWORDS));
        List<String> newsMacroTopics   = parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_TOPICS", DEFAULT_NEWS_MACRO_TOPICS));
        List<String> newsMacroTriggers = parseCsv(resolveOrDefault(dotenv, "NEWS_MACRO_TRIGGERS", DEFAULT_NEWS_MACRO_TRIGGERS));
        List<String> newsFlipKeywords  = parseCsv(resolveOrDefault(dotenv, "NEWS_FLIP_KEYWORDS", DEFAULT_NEWS_FLIP_KEYWORDS));
        List<String> newsExcludeKeywords = parseCsv(resolve(dotenv, "NEWS_EXCLUDE_KEYWORDS"));
        List<String> newsRssFeeds = parseCsv(resolveOrDefault(dotenv, "NEWS_RSS_FEEDS", DEFAULT_NEWS_RSS_FEEDS));
        int newsMaxAgeMin = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_MAX_AGE_MIN", "30"));
        int newsMacroCooldownMin = Integer.parseInt(resolveOrDefault(dotenv, "NEWS_MACRO_COOLDOWN_MIN", "10"));

        // 공통 필수
        if (dartApiKey == null || dartApiKey.isBlank()) {
            throw new IllegalStateException("DART_API_KEY 환경변수가 설정되지 않았습니다.");
        }

        // 알림 선택자별 조건부 필수 검증
        switch (notifier) {
            case "webex" -> {
                if (webexBotToken == null || webexBotToken.isBlank())
                    throw new IllegalStateException("WEBEX_BOT_TOKEN 환경변수가 설정되지 않았습니다.");
                if (webexRoomId == null || webexRoomId.isBlank())
                    throw new IllegalStateException("WEBEX_ROOM_ID 환경변수가 설정되지 않았습니다.");
            }
            case "discord" -> {
                if (discordBotToken == null || discordBotToken.isBlank())
                    throw new IllegalStateException("DISCORD_BOT_TOKEN 환경변수가 설정되지 않았습니다.");
                if (discordChannelId == null || discordChannelId.isBlank())
                    throw new IllegalStateException("DISCORD_CHANNEL_ID 환경변수가 설정되지 않았습니다.");
            }
            default -> throw new IllegalStateException(
                    "알 수 없는 NOTIFIER 값: \"" + notifier + "\". webex 또는 discord 중 하나를 지정하세요.");
        }

        // 네이버 키: 하나만 있으면 설정 실수이므로 즉시 실패. (RSS는 키 없이 동작)
        boolean hasId     = naverClientId != null && !naverClientId.isBlank();
        boolean hasSecret = naverClientSecret != null && !naverClientSecret.isBlank();
        if (hasId != hasSecret) {
            throw new IllegalStateException(
                    "NAVER_CLIENT_ID와 NAVER_CLIENT_SECRET은 둘 다 설정하거나 둘 다 비워야 합니다.");
        }
        if (newsEnabled
                && newsKeywords.isEmpty() && newsBadKeywords.isEmpty() && newsMacroKeywords.isEmpty()) {
            throw new IllegalStateException("뉴스 폴링이 활성화됐지만 키워드가 모두 비어 있습니다.");
        }

        return new AppConfig(dartApiKey, notifier, webexBotToken, webexRoomId, webexNewsRoomId,
                discordBotToken, discordChannelId, discordNewsChannelId, pollInterval, corpCls, pblntfTy,
                filterExtraKeywords, filterExcludeKeywords,
                newsEnabled, naverClientId, naverClientSecret,
                newsPollInterval, newsRssPollInterval,
                newsKeywords, newsBadKeywords, newsMacroKeywords,
                newsMacroTopics, newsMacroTriggers, newsFlipKeywords, newsExcludeKeywords,
                newsRssFeeds, newsMaxAgeMin, newsMacroCooldownMin);
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
