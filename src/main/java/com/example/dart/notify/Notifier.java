package com.example.dart.notify;

/**
 * 알림 채널 추상화 — 완성된 마크다운 메시지를 전달받아 전송만 담당한다.
 * 메시지 조립은 {@link DisclosureEnricher}, 전송 채널 선택은 App(조립 루트)의 책임.
 */
public interface Notifier {
    void start();

    /** 마크다운 메시지 1건 전송. 채널별 길이 제한 초과 시 구현체가 자른다. */
    void send(String message);

    /**
     * {@link #send(String)}를 감싸 실패 시 로그만 남기고 삼킨다 — 알림 1건의 실패가 폴링을
     * 멈추면 안 된다. failLabel은 SLF4J 포맷 문자열이며 args로 보간한다(예외는 자동 부착).
     */
    default void trySend(String message, String failLabel, Object... args) {
        try {
            send(message);
        } catch (Exception e) {
            Object[] all = java.util.Arrays.copyOf(args, args.length + 1);
            all[args.length] = e;
            org.slf4j.LoggerFactory.getLogger(Notifier.class).warn(failLabel, all);
        }
    }

    void stop();
}
