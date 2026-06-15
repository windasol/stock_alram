package com.example.dart.news;

import com.example.dart.config.AppConfig;
import com.example.dart.notify.Notifier;
import com.example.dart.util.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 뉴스 폴러 — 공시 폴러(PollerService)와 독립된 스레드에서 병렬로 동작하며 Notifier만 공유한다.
 *
 * 세 소스를 같은 스레드에서 다른 주기로 폴링한다 (필터 상태 공유, 동기화 불필요):
 *  - RSS      : 언론사 속보 피드 직접 폴링. 포털 색인 지연이 없어 가장 빠르다. 무료·무제한이라 짧은 주기.
 *  - 구글뉴스  : 키워드 검색 RSS. 네이버 API 사각지대(중소매체·외신 한글판) 보완. 비공식 한도라 중간 주기.
 *  - 네이버    : 키워드 검색. RSS에 없는 매체를 잡는 보완망. 일 25,000회 한도라 긴 주기.
 *
 * 빠른 소스가 먼저 알린 이슈는 유사 제목 중복 필터가 느린 소스의 같은 기사를 자동 억제한다.
 * 흐름: 신규 기사(링크 기준) → 키워드 분류(호재/악재/시황) → 노이즈 필터 → 조립 → 전송.
 */
public class NewsPollerService {

    private static final Logger log = LoggerFactory.getLogger(NewsPollerService.class);
    private static final int NAVER_DAILY_LIMIT = 25_000;

    private final RssClient rssClient;
    private final List<RssFeed> rssFeeds;
    private final List<RssFeed> googleFeeds;
    private final NaverNewsClient newsClient;
    private final NewsKeywordClassifier classifier;
    private final NewsArticleFilter articleFilter;
    private final Notifier notifier;
    private final NewsAlertComposer alertComposer;
    private final SeenStore seenStore;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    public NewsPollerService(RssClient rssClient, List<RssFeed> rssFeeds, List<RssFeed> googleFeeds,
                             NaverNewsClient newsClient, NewsKeywordClassifier classifier,
                             NewsArticleFilter articleFilter, Notifier notifier,
                             NewsAlertComposer alertComposer, SeenStore seenStore, AppConfig config) {
        this.rssClient = rssClient;
        this.rssFeeds = rssFeeds;
        this.googleFeeds = googleFeeds;
        this.newsClient = newsClient;
        this.classifier = classifier;
        this.articleFilter = articleFilter;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.seenStore = seenStore;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "news-poller"));
    }

    public void start() {
        int queryCount = config.allNewsKeywords().size();
        long dailyCalls = (long) queryCount * 86_400 / config.newsPollIntervalSec();
        log.info("뉴스 폴링 시작 (RSS {}개 피드 {}초 주기, 구글뉴스 {}개 키워드 {}초 주기, 네이버 키워드 {}개 {}초 주기 — 예상 일 호출 {}회)",
                rssFeeds.size(), config.newsRssPollIntervalSec(),
                googleFeeds.size(), config.newsGooglePollIntervalSec(),
                queryCount, config.newsPollIntervalSec(), dailyCalls);
        if (dailyCalls > NAVER_DAILY_LIMIT) {
            log.warn("네이버 예상 일 호출 수가 한도({}회)를 초과합니다 — NEWS_POLL_INTERVAL_SEC를 늘리거나 키워드를 줄이세요.",
                    NAVER_DAILY_LIMIT);
        }
        scheduler.scheduleWithFixedDelay(this::pollRss, 0, config.newsRssPollIntervalSec(), TimeUnit.SECONDS);
        if (!googleFeeds.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::pollGoogle, 10, config.newsGooglePollIntervalSec(), TimeUnit.SECONDS);
        }
        if (newsClient != null) {
            scheduler.scheduleWithFixedDelay(this::pollNaver, 5, config.newsPollIntervalSec(), TimeUnit.SECONDS);
        }
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("뉴스 폴링 중지 완료");
    }

    private void pollRss() {
        pollFeeds(rssFeeds, "RSS");
    }

    private void pollGoogle() {
        pollFeeds(googleFeeds, "구글뉴스");
    }

    private void pollFeeds(List<RssFeed> feeds, String label) {
        try {
            int fresh = 0, matched = 0, alerted = 0;
            for (RssFeed feed : feeds) {
                for (NewsArticle article : rssClient.fetch(feed)) {
                    if (seenStore.contains(article.link())) continue;
                    seenStore.add(article.link());
                    fresh++;
                    // 피드는 전체 기사 스트림 — 키워드 미매칭은 그냥 버린다.
                    Optional<NewsKeywordClassifier.Match> match = classifier.classify(article.title());
                    if (match.isEmpty()) continue;
                    matched++;
                    if (handle(article, match.get())) alerted++;
                }
            }
            if (fresh > 0) {
                log.info("{} 폴링: 신규 {}건 → 키워드 매칭 {}건 → 알림 {}건", label, fresh, matched, alerted);
            }
        } catch (Exception e) {
            log.error("{} 폴링 중 오류 발생", label, e);
        }
    }

    private void pollNaver() {
        try {
            int fresh = 0, matched = 0, alerted = 0;
            for (String keyword : config.allNewsKeywords()) {
                for (NewsArticle article : newsClient.search(keyword)) {
                    if (seenStore.contains(article.link())) continue;
                    seenStore.add(article.link());
                    fresh++;
                    // 검색어가 넓어서(임상, 적자 등) 제목 분류를 통과한 기사만 알린다 —
                    // 검색어 기준 폴백은 무관한 기사까지 알림을 보내게 된다.
                    Optional<NewsKeywordClassifier.Match> match = classifier.classify(article.title());
                    if (match.isEmpty()) continue;
                    matched++;
                    if (handle(article, match.get())) alerted++;
                }
            }
            if (fresh > 0) {
                log.info("네이버 폴링: 신규 {}건 → 키워드 매칭 {}건 → 알림 {}건", fresh, matched, alerted);
            }
        } catch (Exception e) {
            log.error("네이버 뉴스 폴링 중 오류 발생", e);
        }
    }

    /** @return 알림을 보냈으면 true. */
    private boolean handle(NewsArticle article, NewsKeywordClassifier.Match match) {
        Instant now = Instant.now();
        Optional<String> reject = articleFilter.rejectReason(article, match, now);
        if (reject.isPresent()) {
            log.info("뉴스 필터 제외 [{}]: {}", reject.get(), article.title());
            return false;
        }
        articleFilter.markAlerted(article, match, now);
        log.info("{} 뉴스 감지 [{}|{}]: {}", match.sentiment(), match.keyword(), article.source(), article.title());
        notifier.send(alertComposer.compose(article, match));
        return true;
    }
}
