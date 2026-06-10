package com.example.dart.config;

import io.github.cdimascio.dotenv.Dotenv;

public record AppConfig(
        String dartApiKey,
        String discordBotToken,
        String discordChannelId,
        int pollIntervalSec,
        String corpCls
) {

    public static AppConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String dartApiKey = resolve(dotenv, "DART_API_KEY");
        String discordBotToken = resolve(dotenv, "DISCORD_BOT_TOKEN");
        String discordChannelId = resolve(dotenv, "DISCORD_CHANNEL_ID");
        int pollInterval = Integer.parseInt(resolveOrDefault(dotenv, "POLL_INTERVAL_SEC", "7"));
        String corpCls = resolveOrDefault(dotenv, "CORP_CLS", "Y");

        if (dartApiKey == null || dartApiKey.isBlank()) {
            throw new IllegalStateException("DART_API_KEY 환경변수가 설정되지 않았습니다.");
        }
        if (discordBotToken == null || discordBotToken.isBlank()) {
            throw new IllegalStateException("DISCORD_BOT_TOKEN 환경변수가 설정되지 않았습니다.");
        }
        if (discordChannelId == null || discordChannelId.isBlank()) {
            throw new IllegalStateException("DISCORD_CHANNEL_ID 환경변수가 설정되지 않았습니다.");
        }

        return new AppConfig(dartApiKey, discordBotToken, discordChannelId, pollInterval, corpCls);
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
