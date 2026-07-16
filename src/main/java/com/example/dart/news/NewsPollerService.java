package com.example.dart.news;

import com.example.dart.common.infra.PollWorker;
import com.example.dart.config.AppConfig;
import com.example.dart.common.infra.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 뉴스 폴러 — 공시 폴러(PollerService)와 독립된 스레드에서 병렬로 동작한다.
 *
 * 세 소스를 같은 스레드에서 다른 주기로 폴링한다 (SeenStore 상태 공유, 동기화 불필요):
 *  - RSS      : 언론사 피드 직접 폴링. 포털 색인 지연이 없어 가장 빠르다. 무료·무제한이라 짧은 주기.
 *  - 구글뉴스  : 키워드 검색 RSS. 네이버 API 사각지대(중소매체·외신 한글판) 보완. 비공식 한도라 중간 주기.
 *  - 네이버    : 키워드 검색. RSS에 없는 매체를 잡는 보완망. 일 25,000회 한도라 긴 주기.
 *
 * 예전엔 속보 말머리([속보]·[긴급])만 골라 개별 발송했으나, 그 방식은 "언론사가 속보 딱지를 붙였나"라는
 * 형식 판단일 뿐 트레이딩 가치와 무관해(정치 속보·뻔한 지수 등락) 폐기했다. 지금은 신규 기사를
 * {@link NewsHeadlineBuffer}에 쌓기만 하고, 시황 리포트(KisPollerService)가 지난 1시간치를 읽어
 * "시장 상황·주도 테마/종목"을 분석하는 재료로 쓴다.
 */
public class NewsPollerService {

    private static final Logger log = LoggerFactory.getLogger(NewsPollerService.class);
    private static final int NAVER_DAILY_LIMIT = 25_000;

    private final RssClient rssClient;
    private final List<RssFeed> rssFeeds;
    private final List<RssFeed> googleFeeds;
    private final NaverNewsClient newsClient;
    private final NewsHeadlineBuffer headlineBuffer;
    private final SeenStore seenStore;
    /** 잡음 제거용 제외 키워드(소문자). 제목에 포함되면 버퍼에 넣지 않는다(스포츠·연예 등). */
    private final List<String> excludeKeywords;
    private final AppConfig config;
    private final PollWorker scheduler;

    public NewsPollerService(RssClient rssClient, List<RssFeed> rssFeeds, List<RssFeed> googleFeeds,
                             NaverNewsClient newsClient, NewsHeadlineBuffer headlineBuffer,
                             SeenStore seenStore, AppConfig config) {
        this.rssClient = rssClient;
        this.rssFeeds = rssFeeds;
        this.googleFeeds = googleFeeds;
        this.newsClient = newsClient;
        this.headlineBuffer = headlineBuffer;
        this.seenStore = seenStore;
        this.excludeKeywords = config.newsExcludeKeywords().stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.config = config;
        this.scheduler = new PollWorker("news-poller");
    }

    public void start() {
        int queryCount = config.allNewsKeywords().size();
        long dailyCalls = (long) queryCount * 86_400 / config.newsPollIntervalSec();
        log.info("뉴스 수집 시작 (RSS {}개 피드 {}초 주기, 구글뉴스 {}개 키워드 {}초 주기, 네이버 키워드 {}개 {}초 주기 — 예상 일 호출 {}회, 시황 리포트 재료로 버퍼링)",
                rssFeeds.size(), config.newsRssPollIntervalSec(),
                googleFeeds.size(), config.newsGooglePollIntervalSec(),
                queryCount, config.newsPollIntervalSec(), dailyCalls);
        if (dailyCalls > NAVER_DAILY_LIMIT) {
            log.warn("네이버 예상 일 호출 수가 한도({}회)를 초과합니다 — NEWS_POLL_INTERVAL_SEC를 늘리거나 키워드를 줄이세요.",
                    NAVER_DAILY_LIMIT);
        }
        scheduler.scheduleWithFixedDelay(this::pollRss, 0, config.newsRssPollIntervalSec());
        if (!googleFeeds.isEmpty()) {
            scheduler.scheduleWithFixedDelay(this::pollGoogle, 10, config.newsGooglePollIntervalSec());
        }
        if (newsClient != null) {
            scheduler.scheduleWithFixedDelay(this::pollNaver, 5, config.newsPollIntervalSec());
        }
    }

    public void stop() {
        scheduler.stop();
        log.info("뉴스 수집 중지 완료");
    }

    private void pollRss() {
        pollFeeds(rssFeeds, "RSS");
    }

    private void pollGoogle() {
        pollFeeds(googleFeeds, "구글뉴스");
    }

    private void pollFeeds(List<RssFeed> feeds, String label) {
        try {
            int collected = 0;
            for (RssFeed feed : feeds) {
                for (NewsArticle article : rssClient.fetch(feed)) {
                    if (collect(article)) collected++;
                }
            }
            if (collected > 0) {
                log.info("{} 폴링: 신규 {}건 수집 (버퍼 {}건)", label, collected, headlineBuffer.size());
            }
        } catch (Exception e) {
            log.error("{} 폴링 중 오류 발생", label, e);
        }
    }

    private void pollNaver() {
        try {
            int collected = 0;
            for (String keyword : config.allNewsKeywords()) {
                for (NewsArticle article : newsClient.search(keyword)) {
                    if (collect(article)) collected++;
                }
            }
            if (collected > 0) {
                log.info("네이버 폴링: 신규 {}건 수집 (버퍼 {}건)", collected, headlineBuffer.size());
            }
        } catch (Exception e) {
            log.error("네이버 뉴스 폴링 중 오류 발생", e);
        }
    }

    /**
     * 신규 기사(링크 기준 처음 본 것)를 버퍼에 쌓는다. 제외 키워드가 걸린 제목은 링크만 기억하고 버린다.
     * @return 실제 버퍼에 적재했으면 true.
     */
    private boolean collect(NewsArticle article) {
        if (seenStore.contains(article.link())) return false;
        seenStore.add(article.link());
        if (isExcluded(article.title())) return false;
        headlineBuffer.add(article);
        return true;
    }

    /** 제목에 제외 키워드가 하나라도 포함되면 true(잡음 컷). 설정이 비면 항상 false. */
    private boolean isExcluded(String title) {
        if (excludeKeywords.isEmpty() || title == null) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        for (String kw : excludeKeywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }
}
