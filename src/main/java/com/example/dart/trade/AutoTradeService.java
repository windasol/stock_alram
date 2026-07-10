package com.example.dart.trade;

import com.example.dart.config.AppConfig;
import com.example.dart.kis.KisClient;
import com.example.dart.kis.MinuteCandle;
import com.example.dart.notify.Notifier;
import com.example.dart.util.KoreanMoney;
import com.example.dart.util.MarketCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 공시 기반 자동매매 — Stage 1: 드라이런(모의 시뮬레이션).
 *
 * "단일판매·공급계약" 호재에서 계약금액이 최근 매출액의 임계(기본 50%) 이상이면 진입 신호로 보고,
 * 예산(기본 100만원)으로 몇 주를 샀을지·진입 후 -손절%/+익절%/장마감에 어떻게 청산됐을지를
 * 실제 KIS 분봉으로 감시하며 알림·로그로 남긴다. **실제 주문은 내지 않는다**(mode=dryrun).
 *
 * 트리거는 {@link TradeSignalListener}로 공시 알림 파이프라인(AlertComposer / KIND 폴러)에서 들어온다.
 * 진입 지연(≥임계% 판정이 본문 파싱 후라 1~10분 소요)은 의도적으로 그대로 두고 드라이런으로 실측한다.
 */
public class AutoTradeService implements TradeSignalListener {

    private static final Logger log = LoggerFactory.getLogger(AutoTradeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** 감시 운영시간 — NXT 프리마켓 08:00 ~ 애프터마켓 20:00. 정규장 밖은 통합("UN") 분봉으로 조회. */
    private static final LocalTime TRACK_OPEN = LocalTime.of(8, 0);
    private static final LocalTime TRACK_CLOSE = LocalTime.of(20, 0);
    private static final LocalTime REG_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REG_CLOSE = LocalTime.of(15, 30);

    private final KisClient kisClient;
    private final Notifier notifier;
    private final MarketCalendar calendar;

    private final long budgetWon;
    private final double minSalesRatio;
    private final double stopLossPct;     // 양수(예: 2 = -2%에서 손절)
    private final double takeProfitPct;   // 양수(예: 5 = +5%에서 익절)
    private final int maxPositions;
    private final int monitorSec;
    private final LocalTime eodClose;

    /** 종목코드 → 열린 포지션. 진입(리스너 스레드)·감시(스케줄러 스레드)가 공유해 concurrent. */
    private final Map<String, Position> open = new ConcurrentHashMap<>();
    /** 처리한 공시 접수번호 — 같은 공시로 재진입 방지. */
    private final Set<String> handled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "auto-trade"));

    /** 열린(모의) 포지션 한 건. */
    record Position(String stockCode, String corpName, long entryPrice, long qty,
                    ZonedDateTime entryAt, double salesRatioPct) {
        long budget() { return entryPrice * qty; }
    }

    public AutoTradeService(KisClient kisClient, Notifier notifier, MarketCalendar calendar, AppConfig config) {
        this.kisClient = kisClient;
        this.notifier = notifier;
        this.calendar = calendar;
        this.budgetWon = config.autoTradeBudgetWon();
        this.minSalesRatio = config.autoTradeMinSalesRatio();
        this.stopLossPct = config.autoTradeStopLossPct();
        this.takeProfitPct = config.autoTradeTakeProfitPct();
        this.maxPositions = config.autoTradeMaxPositions();
        this.monitorSec = config.autoTradeMonitorSec();
        this.eodClose = parseTime(config.autoTradeEodClose(), LocalTime.of(15, 20));
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::monitor, monitorSec, monitorSec, TimeUnit.SECONDS);
        log.info("🤖 공시 자동매매(드라이런) 활성 — 트리거: 매출대비≥{}%, 예산 {}, 손절 -{}% / 익절 +{}%, 동시 {}종목, 감시 {}초, 장마감청산 {}",
                trimPct(minSalesRatio), KoreanMoney.format(budgetWon), trimPct(stopLossPct), trimPct(takeProfitPct),
                maxPositions, monitorSec, eodClose);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onContractSignal(String signalId, String corpName, String stockCode, long contractWon,
                                 OptionalLong revenueWon, double salesRatioPct) {
        try {
            if (stockCode == null || stockCode.isBlank()) return;   // 코넥스·비상장 등
            if (salesRatioPct < minSalesRatio) return;              // 임계 미달 — 진입 안 함
            if (signalId != null && !handled.add(signalId)) return; // 같은 공시 중복
            if (open.containsKey(stockCode)) return;                // 이미 보유
            ZonedDateTime now = ZonedDateTime.now(KST);
            if (!withinWindow(now)) {
                log.info("자동매매 진입 생략(장외): {} {} — 매출대비 {}%", stockCode, corpName, trimPct(salesRatioPct));
                return;
            }
            if (open.size() >= maxPositions) {
                log.info("자동매매 진입 생략(동시보유 한도 {}): {} {}", maxPositions, stockCode, corpName);
                return;
            }
            OptionalLong price = currentPrice(stockCode, now);
            if (price.isEmpty()) {
                log.info("자동매매 진입 생략(현재가 조회 실패): {} {}", stockCode, corpName);
                return;
            }
            long entry = price.getAsLong();
            long qty = qtyFor(budgetWon, entry);
            if (qty <= 0) {
                log.info("자동매매 진입 생략(예산<1주, 고가주): {} {} 진입가 {}원", stockCode, corpName, entry);
                return;
            }
            Position p = new Position(stockCode, corpName, entry, qty, now, salesRatioPct);
            open.put(stockCode, p);
            notifier.send(String.format(
                    "🟢 **[모의매수]** %s(%s) · 매출대비 %s%%\n진입 %s원 × %d주 = %s (%s)",
                    corpName, stockCode, trimPct(salesRatioPct),
                    KoreanMoney.format(entry), qty, KoreanMoney.format(p.budget()), CLOCK.format(now)));
            log.info("🟢 [모의매수] {} {} — {}원 × {}주 (매출대비 {}%), 신호 {}",
                    stockCode, corpName, entry, qty, trimPct(salesRatioPct), signalId);
        } catch (Exception e) {
            log.warn("자동매매 진입 처리 실패: {} {}", stockCode, corpName, e);
        }
    }

    /** 감시 루프 — 열린 포지션마다 현재가로 손익%를 재고 손절/익절/장마감 청산한다. */
    private void monitor() {
        if (open.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean tradable = withinWindow(now);
        boolean eod = !now.toLocalTime().isBefore(eodClose);
        for (Position p : open.values()) {
            try {
                if (!tradable) {
                    // 장외 — 분봉 없음. 장마감 이후면 마지막 진입가 기준 청산 처리(모의)로 넘긴다.
                    if (eod) exit(p, p.entryPrice(), 0.0, now, "장마감");
                    continue;
                }
                OptionalLong cur = currentPrice(p.stockCode(), now);
                if (cur.isEmpty()) continue;
                long price = cur.getAsLong();
                double pnl = pnlPct(p.entryPrice(), price);
                if (pnl <= -stopLossPct) {
                    exit(p, price, pnl, now, "손절");
                } else if (pnl >= takeProfitPct) {
                    exit(p, price, pnl, now, "익절");
                } else if (eod) {
                    exit(p, price, pnl, now, "장마감");
                }
            } catch (Exception e) {
                log.warn("자동매매 감시 실패: {} {}", p.stockCode(), p.corpName(), e);
            }
        }
    }

    /** (모의) 청산 — 포지션을 닫고 손익을 알림·로그로 남긴다. */
    private void exit(Position p, long exitPrice, double pnlPct, ZonedDateTime now, String reason) {
        if (open.remove(p.stockCode()) == null) return;   // 이미 다른 경로로 닫힘
        long pnlWon = (exitPrice - p.entryPrice()) * p.qty();
        String emoji = "익절".equals(reason) ? "🔵" : "손절".equals(reason) ? "🔴" : "🟡";
        long heldMin = ChronoUnit.MINUTES.between(p.entryAt(), now);
        notifier.send(String.format(
                "%s **[모의%s]** %s(%s)\n진입 %s → 청산 %s원 · %s%% · 손익 %s원 · 보유 %d분 (%s)",
                emoji, reason, p.corpName(), p.stockCode(),
                KoreanMoney.format(p.entryPrice()), KoreanMoney.format(exitPrice), signed(pnlPct),
                signedWon(pnlWon), heldMin, CLOCK.format(now)));
        log.info("{} [모의{}] {} {} — 진입 {} 청산 {} ({}%), 손익 {}원, 보유 {}분",
                emoji, reason, p.stockCode(), p.corpName(), p.entryPrice(), exitPrice,
                trimPct(pnlPct), pnlWon, heldMin);
    }

    /** 종목의 현재가(최신 분봉 종가). 장외·조회실패면 비어있음. */
    private OptionalLong currentPrice(String code, ZonedDateTime now) {
        LocalTime nowMin = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        String endHms = nowMin.format(HHMMSS);
        boolean extended = nxtSession(nowMin);
        List<MinuteCandle> candles = kisClient.minuteCandles(code, endHms, extended ? "UN" : "J");
        if (candles.isEmpty() && extended) candles = kisClient.minuteCandles(code, endHms, "J");
        if (candles.isEmpty()) return OptionalLong.empty();
        long close = latest(candles).close();
        return close > 0 ? OptionalLong.of(close) : OptionalLong.empty();
    }

    // ── 순수 함수(테스트용) ────────────────────────────────────────────────

    /** 예산으로 살 수 있는 정수 주식 수. 가격이 0 이하면 0. */
    static long qtyFor(long budgetWon, long price) {
        return price <= 0 ? 0 : budgetWon / price;
    }

    /** 진입가 대비 손익률(%). */
    static double pnlPct(long entryPrice, long currentPrice) {
        return entryPrice <= 0 ? 0.0 : (currentPrice - entryPrice) * 100.0 / entryPrice;
    }

    /** 정규장 밖(프리·애프터마켓, NXT만 거래)이면 true — 통합("UN") 분봉 필요. */
    static boolean nxtSession(LocalTime t) {
        return t.isBefore(REG_OPEN) || !t.isBefore(REG_CLOSE);
    }

    private static MinuteCandle latest(List<MinuteCandle> candles) {
        MinuteCandle picked = candles.get(0);
        for (MinuteCandle c : candles) {
            if (c.time().isAfter(picked.time())) picked = c;   // 정렬 가정하지 않고 최신 봉 선택
        }
        return picked;
    }

    private boolean withinWindow(ZonedDateTime now) {
        if (!calendar.isTradingDay(now.toLocalDate())) return false;   // 주말·공휴일
        LocalTime t = now.toLocalTime();
        return !t.isBefore(TRACK_OPEN) && !t.isAfter(TRACK_CLOSE);
    }

    private static LocalTime parseTime(String hhmm, LocalTime fallback) {
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String trimPct(double v) {
        return String.format("%.1f", v);
    }

    private static String signed(double pct) {
        return (pct >= 0 ? "+" : "") + String.format("%.1f", pct);
    }

    private static String signedWon(long won) {
        return (won >= 0 ? "+" : "") + KoreanMoney.format(won);
    }
}
