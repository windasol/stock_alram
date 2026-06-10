package com.example.dart.notify;

import java.util.Map;

public class WebexService extends HttpNotifier {

    private static final String MESSAGES_URL = "https://webexapis.com/v1/messages";
    private static final int MAX_MARKDOWN_LENGTH = 7000;

    private final String botToken;
    private final String roomId;

    public WebexService(String botToken, String roomId) {
        this.botToken = botToken;
        this.roomId = roomId;
    }

    @Override
    public void start() {
        log.info("Webex 알림 서비스 시작 (roomId: {})", roomId);
    }

    @Override
    public void send(String message) {
        postJson(MESSAGES_URL,
                Map.of("Authorization", "Bearer " + botToken),
                Map.of("roomId", roomId,
                       "markdown", truncate(message, MAX_MARKDOWN_LENGTH)));
    }

    @Override
    public void stop() {
        log.info("Webex 알림 서비스 종료");
    }
}
