package com.example.dart.notify;

/**
 * 알림 채널 추상화 — 완성된 마크다운 메시지를 전달받아 전송만 담당한다.
 * 메시지 조립은 {@link DisclosureEnricher}, 전송 채널 선택은 App(조립 루트)의 책임.
 */
public interface Notifier {
    void start();

    /** 마크다운 메시지 1건 전송. 채널별 길이 제한 초과 시 구현체가 자른다. */
    void send(String message);

    void stop();
}
