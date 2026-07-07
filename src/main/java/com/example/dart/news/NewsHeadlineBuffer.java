package com.example.dart.news;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 뉴스 헤드라인 롤링 버퍼 — 뉴스 폴러(writer, news-poller 스레드)가 수집한 기사를 쌓고,
 * 시황 리포트(reader, kis-poller 스레드)가 지난 N분치를 읽어 분석 재료로 쓴다.
 *
 * 개별 속보 발송을 없앤 뒤 뉴스는 "즉시 알림"이 아니라 "시간당 리포트의 맥락"으로만 소비된다.
 * 링크 중복 제거는 호출부(SeenStore)가 담당하므로 여기선 순수 시간창·상한 관리만 한다.
 * 두 스레드가 공유하므로 모든 접근을 동기화한다.
 */
public final class NewsHeadlineBuffer {

    /** 상한 — 폭주 시 메모리 방어. 초과분은 오래된 것부터 버린다. */
    private static final int MAX_ENTRIES = 500;
    /** 보존창 — 리포트 주기(시간당)보다 넉넉히 잡아 밤샘 누적을 막는다. */
    private static final Duration RETENTION = Duration.ofHours(2);

    /** 기사 + 수집 시각(발행 시각이 null일 수 있어 시간창은 수집 시각 기준). */
    private record Entry(NewsArticle article, Instant seenAt) {}

    /** 시간순(오래된→최신) 유지 — addLast로 최신을 뒤에 붙인다. */
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** 기사 1건을 쌓는다. 보존창 밖·상한 초과분을 정리한다. */
    public synchronized void add(NewsArticle article) {
        addAt(article, Instant.now());
    }

    /** {@link #add(NewsArticle)}의 시각 주입 버전 — 시간창 동작을 결정적으로 테스트하기 위함. */
    synchronized void addAt(NewsArticle article, Instant now) {
        entries.addLast(new Entry(article, now));
        evict(now);
    }

    /**
     * now-window 이후 수집된 기사를 최신순(new→old)으로 최대 max건 반환한다.
     * 수집 시각(seenAt) 기준이라 publishedAt이 null이어도 포함한다(재현율 우선).
     */
    public synchronized List<NewsArticle> recent(Duration window, int max) {
        return recentAsOf(window, max, Instant.now());
    }

    /** {@link #recent(Duration, int)}의 시각 주입 버전 — 테스트용. */
    synchronized List<NewsArticle> recentAsOf(Duration window, int max, Instant now) {
        Instant cutoff = now.minus(window);
        List<NewsArticle> out = new ArrayList<>();
        Iterator<Entry> it = entries.descendingIterator();   // 최신부터
        while (it.hasNext() && out.size() < max) {
            Entry e = it.next();
            if (e.seenAt().isBefore(cutoff)) break;   // 시간순이라 하나라도 오래되면 이후도 전부 오래됨
            out.add(e.article());
        }
        return out;
    }

    /** 현재 버퍼 크기(로깅용). */
    public synchronized int size() {
        return entries.size();
    }

    /** 보존창 밖·상한 초과분을 앞(오래된 쪽)에서 제거한다. */
    private void evict(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        while (!entries.isEmpty() && entries.peekFirst().seenAt().isBefore(cutoff)) {
            entries.removeFirst();
        }
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }
}
