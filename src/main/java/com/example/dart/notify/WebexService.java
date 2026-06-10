package com.example.dart.notify;

import com.example.dart.model.Disclosure;
import com.example.dart.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class WebexService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WebexService.class);
    private static final String MESSAGES_URL = "https://webexapis.com/v1/messages";
    private static final int MAX_MARKDOWN_LENGTH = 7000;

    private final String botToken;
    private final String roomId;
    private final DocumentService documentService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public WebexService(String botToken, String roomId, DocumentService documentService) {
        this.botToken = botToken;
        this.roomId = roomId;
        this.documentService = documentService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public void start() {
        log.info("Webex 알림 서비스 시작 (roomId: {})", roomId);
    }

    public void sendBootMessage() {
        send("DART 호재 알림 봇이 시작되었습니다.");
    }

    public void sendTitleAlert(Disclosure d) {
        String dartUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + d.rceptNo();
        String rceptDate = formatDate(d.rceptDt());
        String header = String.format(
                "**[호재 공시]**%n**회사명**: %s%n**공시제목**: %s%n**접수일**: %s%n**제출인**: %s%n**[DART 원문](%s)**",
                d.corpName(), d.reportNm(), rceptDate, d.flrNm(), dartUrl);

        String detail;
        try {
            detail = documentService.buildDetail(d.rceptNo());
        } catch (Exception e) {
            log.warn("상세 내역 조회 실패 — 헤더만 전송: {} - {}", d.corpName(), d.reportNm(), e);
            send(header);
            return;
        }

        String fullText = header + "\n\n---\n" + detail;
        send(truncate(fullText));
    }

    public void stop() {
        log.info("Webex 알림 서비스 종료");
    }

    // ---- private helpers ----

    private void send(String markdown) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "roomId", roomId,
                    "markdown", markdown
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MESSAGES_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("Webex 메시지 전송 완료 (status={})", response.statusCode());
            } else {
                log.error("Webex 메시지 전송 실패: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Webex 메시지 전송 중 오류 발생", e);
        }
    }

    /** "20260610" → "2026-06-10" */
    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_MARKDOWN_LENGTH) return text;
        return text.substring(0, MAX_MARKDOWN_LENGTH - 20) + "\n...(생략)";
    }
}
