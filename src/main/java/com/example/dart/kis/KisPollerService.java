package com.example.dart.kis;

import com.example.dart.config.AppConfig;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * KIS 거래량순위를 주기적으로 스캔해 "확실하게 변동성이 터진" 종목만 알린다 — 공시·뉴스 파이프라인의 보조 정찰망.
 *
 * 판단(모두 충족): 등락률 ≥ 임계 AND 상대거래량(RVOL=누적÷평균) ≥ 임계 AND 거래대금 ≥ 하한.
 * 절대 거래량/거래대금 "순위"가 아니라 RVOL(평소 대비 배수)을 쓰므로 삼성전자·하이닉스 같은 상시 거래대금
 * 상위 대형주는 자동으로 빠지고, 평소 조용하다 갑자기 터진 종목만 남는다.
 *
 * 장중에만 폴링하고(주말·야간 제외), 같은 종목 반복 알림은 쿨다운으로 억제한다.
 */
public class KisPollerService {

    private static final Logger log = LoggerFactory.getLogger(KisPollerService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 폴링 운영시간 — 시장구분에 따라 달라진다.
     *  - KRX(J): 정규장 09:00~15:40(마감 동시호가 여유). NXT 데이터가 없으므로 연장세션을 봐도 의미 없음.
     *  - NXT(UN/NX): 08:00~20:00 (프리마켓 08:00~09:00 · 정규장 09:00~15:30 · 애프터마켓 15:30~20:00).
     * 이 시간 밖(야간·주말)엔 폴링하지 않는다.
     */
    private static final LocalTime KRX_OPEN = LocalTime.of(9, 0);
    private static final LocalTime KRX_CLOSE = LocalTime.of(15, 40);
    private static final LocalTime NXT_OPEN = LocalTime.of(8, 0);
    private static final LocalTime NXT_CLOSE = LocalTime.of(20, 0);
    /** 세션 경계 — NXT 모드에서 알림에 "어느 장 시간대"인지 표시해 정규장 vs NXT 연장세션을 구분한다. */
    private static final LocalTime MAIN_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MAIN_CLOSE = LocalTime.of(15, 30);

    private final KisClient client;
    private final Notifier notifier;
    private final KisAlertComposer alertComposer;
    private final int intervalSec;
    private final double minChangePct;
    private final double minRvol;
    private final long minTradeAmountWon;
    private final long minPrice;
    private final Duration cooldown;
    /** UN/NX 시장구분이면 NXT 모드 — 운영시간을 08:00~20:00으로 확장하고 세션(프리/정규/애프터)을 라벨링한다. */
    private final boolean nxtMode;
    private final LocalTime open;
    private final LocalTime close;

    /** 종목코드 → 마지막 알림 시각. 쿨다운 내 재알림 억제. */
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private int consecutiveFailures = 0;
    private int skipPolls = 0;

    public KisPollerService(KisClient client, Notifier notifier,
                            KisAlertComposer alertComposer, AppConfig config) {
        this.client = client;
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.intervalSec = config.kisPollIntervalSec();
        this.minChangePct = config.kisMinChangePct();
        this.minRvol = config.kisMinRvol();
        this.minTradeAmountWon = config.kisMinTradeAmountWon();
        this.minPrice = config.kisMinPrice();
        this.cooldown = Duration.ofMinutes(config.kisCooldownMin());
        String mkt = config.kisMarketDivCode();
        this.nxtMode = "UN".equals(mkt) || "NX".equals(mkt);
        this.open = nxtMode ? NXT_OPEN : KRX_OPEN;
        this.close = nxtMode ? NXT_CLOSE : KRX_CLOSE;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kis-poller"));
    }

    public void start() {
        log.info("KIS 변동성 폴링 시작 (시장 {}{}, 주기 {}초, 운영 {}~{} KST, 임계 등락률≥{}% RVOL≥{}배 거래대금≥{})",
                client.marketDivCode(), nxtMode ? "(NXT 연장세션 포함)" : "",
                intervalSec, open, close, minChangePct, minRvol, minTradeAmountWon);
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSec, TimeUnit.SECONDS);
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
        log.info("KIS 변동성 폴링 중지 완료");
    }

    private void poll() {
        if (skipPolls > 0) {
            skipPolls--;
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!isMarketOpen(now)) return;

        List<VolumeRankItem> items;
        try {
            items = client.volumeRankByIncrease(minPrice);
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            skipPolls = Math.min(1 << consecutiveFailures, 40);
            log.warn("KIS 조회 실패 ({}연속) — {}회 폴링 건너뜀: {}",
                    consecutiveFailures, skipPolls, e.toString());
            return;
        }

        Instant nowInstant = now.toInstant();
        // 정규장(KRX) 모드에선 시간대 구분이 의미 없으므로 "정규장" 고정. NXT 모드에서만 프리/애프터마켓을 라벨링한다.
        String session = nxtMode ? sessionLabel(now.toLocalTime()) : "정규장";
        for (VolumeRankItem it : items) {
            if (!isVolatilitySpike(it, minChangePct, minRvol, minTradeAmountWon)) continue;
            if (!cooledDown(it.code(), nowInstant)) continue;
            lastAlert.put(it.code(), nowInstant);
            log.info("변동성 급등 [{} {} {}]: {}% RVOL {}배 거래대금 {}원",
                    session, it.name(), it.code(), it.changePct(), String.format("%.1f", it.rvol()), it.tradeAmountWon());
            try {
                notifier.send(alertComposer.compose(it, session));
            } catch (Exception e) {
                log.warn("변동성 알림 전송 실패: {} {}", it.name(), it.code(), e);
            }
        }
    }

    /** 감지 시각이 속한 시장 세션 라벨 — 정규장 vs NXT 프리/애프터마켓 구분(알림 표시용). (순수 함수 — 테스트용) */
    static String sessionLabel(LocalTime t) {
        if (t.isBefore(MAIN_OPEN))  return "🌅 NXT 프리마켓";
        if (t.isBefore(MAIN_CLOSE)) return "정규장";
        return "🌆 NXT 애프터마켓";
    }

    /** 등락률·RVOL·거래대금 임계를 모두 넘는 "확실히 터진" 종목인지. (순수 함수 — 테스트용) */
    static boolean isVolatilitySpike(VolumeRankItem it, double minChangePct, double minRvol, long minTradeAmountWon) {
        return it.changePct() >= minChangePct
                && it.rvol() >= minRvol
                && it.tradeAmountWon() >= minTradeAmountWon;
    }

    private boolean cooledDown(String code, Instant now) {
        Instant last = lastAlert.get(code);
        return last == null || Duration.between(last, now).compareTo(cooldown) >= 0;
    }

    private boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(open) && !t.isAfter(close);
    }
}
