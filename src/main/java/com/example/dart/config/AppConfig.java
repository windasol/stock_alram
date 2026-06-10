package com.example.dart.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;

public record AppConfig(
        String dartApiKey,
        String notifier,
        String webexBotToken,
        String webexRoomId,
        String discordBotToken,
        String discordChannelId,
        int pollIntervalSec,
        String corpCls,
        String pblntfTy,
        List<String> filterExtraKeywords,
        List<String> filterExcludeKeywords
) {

    public static AppConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String dartApiKey      = resolve(dotenv, "DART_API_KEY");
        String notifier        = resolveOrDefault(dotenv, "NOTIFIER", "webex").toLowerCase();
        String webexBotToken   = resolve(dotenv, "WEBEX_BOT_TOKEN");
        String webexRoomId     = resolve(dotenv, "WEBEX_ROOM_ID");
        String discordBotToken = resolve(dotenv, "DISCORD_BOT_TOKEN");
        String discordChannelId = resolve(dotenv, "DISCORD_CHANNEL_ID");
        int pollInterval = Integer.parseInt(resolveOrDefault(dotenv, "POLL_INTERVAL_SEC", "7"));
        String corpCls  = resolveOrDefault(dotenv, "CORP_CLS",   "Y,K");
        // B=주요사항보고(공급계약·자사주취득 등), I=거래소공시(수주 등)
        String pblntfTy = resolveOrDefault(dotenv, "PBLNTF_TY",  "B,I");
        List<String> filterExtraKeywords   = parseCsv(resolve(dotenv, "FILTER_EXTRA_KEYWORDS"));
        List<String> filterExcludeKeywords = parseCsv(resolve(dotenv, "FILTER_EXCLUDE_KEYWORDS"));

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

        return new AppConfig(dartApiKey, notifier, webexBotToken, webexRoomId,
                discordBotToken, discordChannelId, pollInterval, corpCls, pblntfTy,
                filterExtraKeywords, filterExcludeKeywords);
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
