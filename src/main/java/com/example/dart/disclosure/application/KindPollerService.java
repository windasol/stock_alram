package com.example.dart.disclosure.application;

import com.example.dart.disclosure.domain.ContractInfo;
import com.example.dart.common.infra.AbstractPoller;
import com.example.dart.common.infra.PollBackoff;
import com.example.dart.common.infra.PollWorker;
import com.example.dart.common.infra.RetryScheduler;
import com.example.dart.config.AppConfig;
import com.example.dart.disclosure.domain.NewsFilter;
import com.example.dart.disclosure.infra.KindClient;
import com.example.dart.disclosure.infra.KindDisclosure;
import com.example.dart.disclosure.infra.KindDocumentClient;
import com.example.dart.notify.Notifier;
import com.example.dart.disclosure.infra.DocumentParser;
import com.example.dart.common.infra.StockQuoteClient;
import com.example.dart.disclosure.domain.DisclosureKeys;
import com.example.dart.common.domain.KoreanMoney;
import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.common.infra.SeenStore;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KIND 오늘의공시 폴러 — DART 폴러(PollerService)와 독립된 스레드에서 병렬로 동작한다.
 * 같은 공시가 양쪽에 게시되므로 공유 교차 중복 키 저장소(DisclosureKeys)로 먼저 잡은 쪽만 알린다.
 *
 * 흐름: 신규(접수번호 기준) → 시장 필터(CORP_CLS) → 제목 필터(NewsFilter 재사용)
 *      → 나이 제한 → 교차 중복 → 알림.
 * 본문(Stage 2) 필터는 생략 — KIND 뷰어 원문 파싱은 별도 작업이고, 수주공급계약의
 * 소액 계약 오탐을 약간 감수하는 대신 속도를 얻는다 (재현율 우선).
 */
public class KindPollerService extends AbstractPoller {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 교차중복 키 날짜 포맷 — DART rcept_dt(yyyyMMdd)와 동일해야 같은 공시가 매칭된다. */
    private static final DateTimeFormatter KEY_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** KIND 공시 게시 시간대 밖에는 폴링하지 않는다 — 야간 무의미 트래픽 방지 (DART 폴러는 24시간 커버). */
    private static final LocalTime OPEN = LocalTime.of(7, 0);
    private static final LocalTime CLOSE = LocalTime.of(20, 0);

    /**
     * 게시된 지 이만큼 지난 공시는 기록만 하고 알리지 않는다 —
     * 첫 실행·재시작 시 당일 누적 공시(DART가 이미 알린 것 포함)가 한꺼번에 쏟아지는 것을 막는다.
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(30);

    /** KIND 본문은 보통 즉시 공개되지만, 순간 미반영 대비로 짧게 재시도한다. */
    private static final long ENRICH_RETRY_DELAY_SEC = 15;
    private static final int ENRICH_MAX_ATTEMPTS = 4;

    private final KindClient client;
    private final NewsFilter newsFilter;
    private final Notifier notifier;
    private final KindAlertComposer alertComposer;
    private final KindDocumentClient docClient;
    private final DocumentParser documentParser;
    private final StockQuoteClient quoteClient;
    private final SeenStore seenStore;
    private final SeenStore disclosureKeys;
    /** 거래일 판정(주말·공휴일). 휴장일엔 거래소 공시가 없어 폴링을 건너뛴다. */
    private final MarketCalendar calendar;
    private final Set<String> allowedMarkets;
    private final PollWorker enrichmentPool;
    private final RetryScheduler enrichRetry;
    /** 자동매매 트리거 리스너 — 비활성 시 null(무동작). 계약 규모 확정 시점에 호출한다. */
    private com.example.dart.trade.TradeSignalListener tradeListener;

    private final PollBackoff backoff = new PollBackoff();

    public KindPollerService(KindClient client, NewsFilter newsFilter, Notifier notifier,
                             KindAlertComposer alertComposer, KindDocumentClient docClient,
                             DocumentParser documentParser, StockQuoteClient quoteClient,
                             SeenStore seenStore, SeenStore disclosureKeys, AppConfig.KindConfig config,
                             MarketCalendar calendar) {
        super("kind-poller", config.pollIntervalSec(),
                String.format("KIND 폴링 시작 (주기: %d초, 운영시간 %s~%s KST)", config.pollIntervalSec(), OPEN, CLOSE));
        this.client = client;
        this.calendar = calendar;
        this.newsFilter = newsFilter;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.docClient = docClient;
        this.documentParser = documentParser;
        this.quoteClient = quoteClient;
        this.seenStore = seenStore;
        this.disclosureKeys = disclosureKeys;
        // DART와 같은 CORP_CLS 설정 공유 — 빈 값이면 전체 시장
        this.allowedMarkets = config.corpCls() == null || config.corpCls().isBlank()
                ? Set.of()
                : Arrays.stream(config.corpCls().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.enrichmentPool = new PollWorker("kind-enrich", 2);
        this.enrichRetry = new RetryScheduler(enrichmentPool, ENRICH_RETRY_DELAY_SEC, ENRICH_MAX_ATTEMPTS);
    }

    /** 자동매매 트리거 리스너를 등록한다(선택). null이면 자동매매 미동작. */
    public void setTradeSignalListener(com.example.dart.trade.TradeSignalListener listener) {
        this.tradeListener = listener;
    }

    @Override
    protected void onStop() {
        enrichmentPool.stop();
        log.info("KIND 폴링 중지 완료");
    }

    @Override
    protected void poll() {
        if (backoff.shouldSkip()) return;
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!calendar.isTradingDay(now.toLocalDate())) return;   // 주말·공휴일엔 거래소 공시 없음
        LocalTime t = now.toLocalTime();
        if (t.isBefore(OPEN) || t.isAfter(CLOSE)) return;

        List<KindDisclosure> disclosures;
        try {
            disclosures = client.fetchToday();
            backoff.success();
        } catch (Exception e) {
            // 연속 실패 시 폴링을 건너뛰며 점진 백오프 — 차단·점검 중 무의미한 재시도 억제
            int skips = backoff.failure();
            log.warn("KIND 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    backoff.consecutiveFailures(), skips, e.toString());
            return;
        }

        for (KindDisclosure d : disclosures) {
            if (!allowedMarkets.isEmpty() && !allowedMarkets.contains(d.marketCode())) continue;
            if (!seenStore.add(d.acptNo())) continue;
            handle(d, now);
        }
    }

    /** 신규 공시 1건 처리: 제목 필터·나이·교차 중복 통과 시 알림 전송. */
    private void handle(KindDisclosure d, ZonedDateTime now) {
        Optional<NewsFilter.TitleMatch> match = newsFilter.matchTitle(d.title());
        if (match.isEmpty()) return;

        if (olderThanMaxAge(d.time(), now)) {
            log.info("KIND 나이 제한 제외 (게시 {}): {} - {}", d.time(), d.company(), d.title());
            return;
        }

        // DART 폴러가 먼저 알린 공시면 건너뛴다 — add가 원자적이라 한쪽만 true를 받는다.
        // 키 날짜는 DART rcept_dt와 맞추기 위해 폴링 시점(KST)의 오늘 날짜를 쓴다.
        String today = now.toLocalDate().format(KEY_DATE_FMT);
        if (!disclosureKeys.add(DisclosureKeys.of(today, d.company(), d.title()))) {
            log.info("교차 중복 제외 (DART 선행 알림): {} - {}", d.company(), d.title());
            return;
        }

        log.info("호재 공시 감지 [KIND|{}|{}|{}]: {} - {}",
                d.market(), match.get().category(), match.get().matchedKeyword(), d.company(), d.title());
        // 1단계: 감지 즉시 헤더
        notifier.send(alertComposer.compose(d, match.get()));
        // 2단계: 비정정 수주공급계약만 KIND 뷰어 본문으로 규모 분석(계약금액 대비)을 후송한다.
        // 비계약·정정 호재의 시총·매출은 매출 출처(corp_code)가 DART에만 있어 DART 폴러가 보강하므로 여기선 생략.
        // (정정 본문은 정정전/정정후가 섞여 계약금액 파싱이 틀리므로 비율 분석 자체를 건너뛴다.)
        if (NewsFilter.CATEGORY_CONTRACT.equals(match.get().category())
                && !NewsFilter.isCorrection(d.title())) {
            scheduleEnrichment(d);
        } else if (NewsFilter.isStockCancellation(d.title())) {
            // 주식소각결정 — KIND 뷰어 본문에서 소각예정금액을 뽑아 시총 대비%와 함께 보강한다.
            // DART 폴러는 KIND 선행 시 소각 보강을 생략(PollerService)하므로 이 경로가 유일한 보강이다.
            scheduleCancellationEnrichment(d);
        }
    }

    /**
     * KIND 뷰어 본문을 파싱해 규모 분석 후속 메시지를 보낸다. KIND 선행 공시는 DART가 보강을 건너뛰므로
     * (PollerService 참고) 이 경로가 유일한 규모 분석이다 — 본문은 DART 원문 지연과 무관하게 즉시 받을 수 있다.
     */
    private void scheduleEnrichment(KindDisclosure d) {
        enrichRetry.run(() -> {
            KindDocumentClient.KindDocument doc = docClient.fetch(d.acptNo());
            ContractInfo c =
                    documentParser.extractContractFromText(documentParser.htmlToPlainText(doc.bodyHtml()));
            OptionalLong cap = doc.stockCode() != null
                    ? quoteClient.marketCapWon(doc.stockCode())
                    : OptionalLong.empty();
            log.info("KIND 규모 분석 [{} - {}] 계약금액 {}", d.company(), d.title(),
                    c.contractWon().isPresent() ? KoreanMoney.format(c.contractWon().getAsLong()) : "미추출");
            notifier.send(alertComposer.composeFollowup(d, c, cap));
            fireTradeSignal(d, doc.stockCode(), c);
        }, (e, attempt) -> log.info("KIND 본문 보강 실패 — {}초 뒤 재시도 ({}/{}): {} - {} ({})",
                ENRICH_RETRY_DELAY_SEC, attempt, ENRICH_MAX_ATTEMPTS, d.company(), d.title(), e.toString()),
        e -> {
            log.warn("KIND 본문 보강 {}회 실패 — 규모 분석 생략: {} - {}",
                    ENRICH_MAX_ATTEMPTS, d.company(), d.title(), e);
            notifier.send(String.format("📊 **시총·매출 대비** | %s — %s\n상세 조회 실패",
                    d.company(), d.title()));
        });
    }

    /**
     * 주식소각결정 — KIND 뷰어 본문에서 소각예정금액을 파싱해 시총 대비%와 함께 후송한다.
     * 본문 조회 실패만 재시도하고, 조회는 됐으나 금액을 못 뽑으면 금액 줄 없이 시총만 보낸다
     * (계약 규모 분석과 동일한 관용). 본문은 DART 원문 지연과 무관하게 즉시 받을 수 있다.
     */
    private void scheduleCancellationEnrichment(KindDisclosure d) {
        enrichRetry.run(() -> {
            KindDocumentClient.KindDocument doc = docClient.fetch(d.acptNo());
            OptionalLong amount = documentParser.cancellationAmountWon(
                    documentParser.htmlToPlainText(doc.bodyHtml()));
            OptionalLong cap = doc.stockCode() != null
                    ? quoteClient.marketCapWon(doc.stockCode())
                    : OptionalLong.empty();
            log.info("KIND 소각금액 분석 [{} - {}] {}", d.company(), d.title(),
                    amount.isPresent() ? KoreanMoney.format(amount.getAsLong()) : "미추출");
            notifier.send(alertComposer.composeCancellation(d, amount, cap));
        }, (e, attempt) -> log.info("KIND 소각 보강 실패 — {}초 뒤 재시도 ({}/{}): {} - {} ({})",
                ENRICH_RETRY_DELAY_SEC, attempt, ENRICH_MAX_ATTEMPTS, d.company(), d.title(), e.toString()),
        e -> {
            log.warn("KIND 소각 보강 {}회 실패 — 소각금액 생략: {} - {}",
                    ENRICH_MAX_ATTEMPTS, d.company(), d.title(), e);
            notifier.send(String.format("📊 **시총·소각금액** | %s — %s\n상세 조회 실패",
                    d.company(), d.title()));
        });
    }

    /** 계약 규모 확정 시 자동매매 리스너에 신호 전달(KIND 경로) — 리스너 없음·종목코드 없음·계약금액/비율 미상이면 무시. */
    private void fireTradeSignal(KindDisclosure d, String stockCode, ContractInfo c) {
        if (tradeListener == null || stockCode == null || stockCode.isBlank() || c.contractWon().isEmpty()) return;
        long won = c.contractWon().getAsLong();
        java.util.OptionalDouble ratio =
                ContractInfo.salesRatioValue(won, c.recentRevenueWon(), c.salesRatioPct());
        if (ratio.isEmpty()) return;
        try {
            tradeListener.onContractSignal(d.acptNo(), d.company(), stockCode, won,
                    c.recentRevenueWon(), ratio.getAsDouble());
        } catch (Exception e) {
            log.warn("자동매매 신호 전달 실패(KIND): {} - {}", d.company(), d.title(), e);
        }
    }

    private static boolean olderThanMaxAge(String hhmm, ZonedDateTime now) {
        try {
            LocalTime published = LocalTime.parse(hhmm);
            ZonedDateTime publishedAt = now.with(published);
            return publishedAt.isBefore(now.minus(MAX_AGE));
        } catch (Exception e) {
            return false;  // 시각을 알 수 없으면 통과 — 재현율 우선
        }
    }
}
