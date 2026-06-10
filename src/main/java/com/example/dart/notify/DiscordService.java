package com.example.dart.notify;

import java.util.Map;

public class DiscordService extends HttpNotifier {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final String botToken;
    private final String channelId;

    public DiscordService(String botToken, String channelId) {
        this.botToken = botToken;
        this.channelId = channelId;
    }

    @Override
    public void start() {
        log.info("Discord 알림 서비스 시작 (channelId: {})", channelId);
    }

    @Override
    public void send(String message) {
        postJson("https://discord.com/api/v10/channels/" + channelId + "/messages",
                Map.of("Authorization", "Bot " + botToken),
                Map.of("content", truncate(message, MAX_MESSAGE_LENGTH)));
    }

    @Override
    public void stop() {
        log.info("Discord 알림 서비스 종료");
    }
}
