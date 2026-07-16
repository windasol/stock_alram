package com.example.dart.pricetrack.application;

import com.example.dart.common.domain.TradingSession;
import com.example.dart.common.infra.PollWorker;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.kis.domain.MinuteCandle;
import com.example.dart.disclosure.domain.Disclosure;
import com.example.dart.notify.Notifier;
import com.example.dart.pricetrack.domain.Stats;
import com.example.dart.common.infra.StockQuoteClient;
import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.common.infra.HttpJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 호재 공시 감지 시각(t0)을 기준으로 "공시 후 10분간 어떻게 움직였나"를 통계로 남긴다.
 *
 * 흐름(재설계): 감지 즉시 폴링하지 않고, 공시 시각 +10분(+버퍼) 뒤에 딱 한 번 KIS 1분봉으로
 * [공시-2분 ~ 공시+10분] 창을 통째로 조회해 분석한다. 10초마다 현재가를 메모리에 적립하던 방식을
 * 없앤 것 — 메모리/네트워크 부담이 작고, 기준가를 "공시 2분 전"으로 잡아 공시 직전 급등(VI 등)에
 * 오염되지 않은 깨끗한 출발점에서 변동을 잰다.
 *
 * 기준가(시작가)는 공시 2분 전 봉의 종가다. 고점/저점/종료가는 공시 시점 이후 구간에서만 본다.
 *
 * 추적 시간대는 NXT 연장세션을 포함한 08:00~20:00(평일). 그 밖(마감 후·새벽·주말)에 뜬 공시는
 * 의미가 없어 건너뛴다. 공시+10분이 마감(20:00)을 넘으면 마감까지로 잘라 분석한다.
 *
 * 종목코드가 없는 공시(코넥스·비상장 등)나 KIS 미설정 시엔 추적하지 않는다(분봉 조회 불가).
 */
public class DisclosurePriceTracker {

    private static final Logger log = LoggerFactory.getLogger(DisclosurePriceTracker.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    /** 고점/저점 발생 시각 표기용 — "10:07". */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final int PRE_MIN = 2;       // 기준가를 잡는 공시 전 시점(분)
    private static final int WINDOW_MIN = 10;   // 공시 후 분석 창(분)
    /**
     * 공시 직전 가격은 헤더가 나간 '뒤'에 발송되도록 살짝 지연시킨다 — KIS 조회는 이미 별도 스레드라
     * 공시 알림을 지연시키지 않지만, 이 지연으로 "공시 먼저, 가격은 그 뒤" 순서를 확실히 보장한다.
     */
    private static final long ENTRY_PRICE_DELAY_SEC = 2;
    /** "100만원이면 몇 주?" 가늠용 기준 예산(원) — 진입가 줄에 100만원 ≈ N주를 함께 보여준다. */
    private static final long BUDGET_WON = 1_000_000L;
    /** 분석 예약 지연 — 공시+10분 봉이 완성되도록 30초 버퍼를 더한다. */
    private static final long ANALYZE_DELAY_SEC = WINDOW_MIN * 60L + 30;

    /** 표기 %의 기준이 되는 전일종가 조회용(previousCloseWon). 시총 등 다른 조회 일관성도 겸한다. */
    private final StockQuoteClient quoteClient;
    private final KisClient kisClient;
    private final Notifier notifier;
    private final Path storeFile;
    /** 거래일 판정(주말·공휴일). 휴장일에 뜬 공시는 분봉이 없어 추적을 건너뛴다. */
    private final MarketCalendar calendar;
    private final PollWorker pool = new PollWorker("price-tracker", 2);

    public DisclosurePriceTracker(StockQuoteClient quoteClient, KisClient kisClient,
                                  Notifier notifier, Path storeFile, MarketCalendar calendar) {
        this.quoteClient = quoteClient;
        this.kisClient = kisClient;
        this.notifier = notifier;
        this.storeFile = storeFile;
        this.calendar = calendar;
    }

    /**
     * 공시 1건 추적을 예약한다(비동기). 공시 시각 +10분 뒤에 분봉 1콜로 분석한다.
     * 종목코드 없음·KIS 미설정·추적 시간대 아님이면 조용히 건너뛴다.
     */
    public void track(Disclosure d, String category) {
        String code = d.stockCode();
        if (code == null || code.isBlank()) return;   // 코넥스·비상장 등 — 추적 불가
        if (kisClient == null) {
            log.info("주가 추적 생략(KIS 미설정 — 분봉 조회 불가): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        ZonedDateTime t0 = ZonedDateTime.now(KST);
        if (!withinWindow(t0)) {
            log.info("주가 추적 생략(장외): {} - {}", d.corpName(), d.reportNm());
            return;
        }
        pool.schedule(() -> analyze(d, category, t0), ANALYZE_DELAY_SEC);
        log.info("주가 추적 예약 [{}] {} - {} — {}분 뒤 [공시-{}분~공시+{}분] 분봉 분석",
                code, d.corpName(), d.reportNm(), WINDOW_MIN, PRE_MIN, WINDOW_MIN);
    }

    /**
     * 공시 알림 직후 "공시 {@value #PRE_MIN}분 전 가격"을 한 줄로 즉시 보낸다(비동기).
     * 알림을 받자마자 어디서 출발했는지 가늠할 기준점을 준다 — 값은 KIS 1분봉의 {@value #PRE_MIN}분 전 봉 종가,
     * %는 전일종가 대비 당일 등락률(사용자가 종목 화면에서 보는 그 %). 10분 뒤 {@link #analyze}의 시작가와 같은 기준점이다.
     * 종목코드 없음·KIS 미설정·장외·분봉 없음이면 조용히 생략한다.
     */
    public void sendEntryPrice(Disclosure d) {
        String code = d.stockCode();
        if (code == null || code.isBlank() || kisClient == null) return;   // 코넥스·비상장·KIS 미설정
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!withinWindow(now)) return;   // 장외 — 분봉 없음
        // 별도 스레드 + 지연 발송 — 공시 헤더는 폴러 스레드에서 즉시 나가고, KIS 조회·가격줄은 그 뒤에.
        pool.schedule(() -> {
            try {
                LocalTime nowMin = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
                LocalTime target = nowMin.minusMinutes(PRE_MIN);
                if (target.isBefore(TradingSession.EXTENDED_OPEN)) target = TradingSession.EXTENDED_OPEN;
                String endHms = nowMin.format(HHMMSS);
                // 정규장 밖(프리·애프터마켓, NXT만 거래)이면 통합("UN") 분봉이 필요. UN이 비면 J로 폴백(analyze와 동일).
                boolean extended = TradingSession.nxtSession(target) || TradingSession.nxtSession(nowMin);
                List<MinuteCandle> candles = kisClient.minuteCandlesWithFallback(code, endHms, extended);
                MinuteCandle base = pickAtOrBefore(candles, target);
                if (base == null) {
                    log.info("공시 직전 가격 생략(분봉 없음): {} - {}", d.corpName(), d.reportNm());
                    return;
                }
                long prdyClose = quoteClient.previousCloseWon(code).orElse(0L);
                notifier.send(composeEntryPrice(base, prdyClose));
            } catch (Exception e) {
                log.warn("공시 직전 가격 스냅샷 실패: {} - {}", d.corpName(), d.reportNm(), e);
            }
        }, ENTRY_PRICE_DELAY_SEC);
    }

    public void stop() {
        pool.stop();
    }

    /** 공시+10분 뒤 한 번 실행 — 분봉을 조회해 통계를 내고 알림·저장한다. */
    private void analyze(Disclosure d, String category, ZonedDateTime t0) {
        try {
            LocalTime t0Min = t0.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            LocalTime fromMin = t0Min.minusMinutes(PRE_MIN);
            if (fromMin.isBefore(TradingSession.EXTENDED_OPEN)) fromMin = TradingSession.EXTENDED_OPEN;
            LocalTime toMin = t0Min.plusMinutes(WINDOW_MIN);
            if (toMin.isAfter(TradingSession.EXTENDED_CLOSE)) toMin = TradingSession.EXTENDED_CLOSE;

            // 시장구분 자동 선택: 창[fromMin~toMin]이 정규장(09:00~15:30) 안에 다 들어오면 KRX("J"),
            // 한쪽이라도 정규장 밖(프리·애프터마켓, NXT만 거래)이면 통합("UN")으로 받는다(경계 공시도 커버).
            // UN이 비거나(미지원·무권한) 데이터가 없으면 J로 한 번 더 시도 — 정규장은 절대 안 깨지게.
            String endHms = toMin.format(HHMMSS);
            boolean extended = TradingSession.nxtSession(fromMin) || TradingSession.nxtSession(toMin);
            List<MinuteCandle> candles = kisClient.minuteCandlesWithFallback(d.stockCode(), endHms, extended);
            // 표기 %의 기준 = 전일종가(당일 등락률). 조회 실패 시 0 → Stats.compute가 기준가 대비로 폴백.
            long prdyClose = quoteClient.previousCloseWon(d.stockCode()).orElse(0L);
            Stats st = Stats.compute(candles, t0Min, fromMin, toMin, prdyClose);
            if (st == null) {
                log.warn("주가 추적 생략(분봉 부족 — NXT 연장세션·데이터 없음): {} {} - {}",
                        d.stockCode(), d.corpName(), d.reportNm());
                return;
            }

            log.info("주가 추적 종료 [{}] {} — 전일종가 {}원/기준가 {}원(공시 {}분 전) / 종료 {}%({}분) / 고점 {}%({}분) / 저점 {}%({}분) / {} (%=전일종가 대비 등락률)",
                    d.stockCode(), d.corpName(), st.prdyClose(), st.baseline(), st.baselineOffsetMin(),
                    round1(st.endPct()), st.endMin(), round1(st.mfePct()), st.peakMin(),
                    round1(st.maePct()), st.troughMin(), st.pattern());

            notifier.send(composeMessage(d, st, t0));
            persist(d, category, t0, st);
        } catch (Exception e) {
            log.warn("주가 추적 분석 오류: {} - {}", d.corpName(), d.reportNm(), e);
        }
    }

    /**
     * 종료 메시지 조립. 모든 %는 전일종가 대비 당일 등락률(사용자가 종목 화면에서 보는 그 %).
     * 공시 시작 시각·시작가(공시 2분전 종가)+등락률, 10분 뒤 가격+등락률을 한 줄에 보여주고,
     * 고점/저점도 그 시점의 등락률과 실제 시계 시각(예: 10:07)으로 표기한다.
     */
    private static String composeMessage(Disclosure d, Stats st, ZonedDateTime t0) {
        return String.format(
                "📊 **공시 후 %d분 주가** | %s — %s\n"
                        + "공시 %s 시작가 %,d원 (%+.1f%%) → %d분 뒤 %,d원 (%+.1f%%)\n"
                        + "🔺 고점 %+.1f%% %,d원 (%s)\n"
                        + "🔽 저점 %+.1f%% %,d원 (%s)\n"
                        + "패턴: %s · %s",
                st.endMin(), d.corpName(), d.reportNm(),
                CLOCK.format(t0.toLocalTime()), st.discPrice(), st.discPct(),
                st.endMin(), st.endPrice(), st.endPct(),
                st.mfePct(), st.peakPrice(), CLOCK.format(st.peakAt()),
                st.maePct(), st.troughPrice(), CLOCK.format(st.troughAt()),
                startDirection(st.discPrice(), st.endPrice()), st.pattern());
    }

    private void persist(Disclosure d, String category, ZonedDateTime t0, Stats st) {
        try {
            ObjectNode o = HttpJson.MAPPER.createObjectNode();
            o.put("t0", t0.toString());
            o.put("rceptNo", d.rceptNo());
            o.put("corpName", d.corpName());
            o.put("reportNm", d.reportNm());
            o.put("market", d.marketName());
            o.put("category", category);
            o.put("code", d.stockCode());
            o.put("prdyCloseWon", st.prdyClose());      // 전일종가(표기 %의 기준, 0이면 기준가로 폴백)
            o.put("baseWon", st.baseline());            // 기준가(공시 N분 전 종가, 패턴 분류 기준점)
            o.put("baseOffsetMin", st.baselineOffsetMin());
            o.put("discWon", st.discPrice());           // 시작가(공시 2분전 종가 = baseWon과 동일)
            o.put("discPct", round1(st.discPct()));     // 시작가의 당일 등락률(전일종가 대비)
            o.put("endWon", st.endPrice());
            o.put("endMin", st.endMin());
            o.put("endPct", round1(st.endPct()));
            o.put("mfePct", round1(st.mfePct()));
            o.put("mfeMin", st.peakMin());
            o.put("mfeAt", CLOCK.format(st.peakAt()));
            o.put("mfeWon", st.peakPrice());
            o.put("maePct", round1(st.maePct()));
            o.put("maeMin", st.troughMin());
            o.put("maeAt", CLOCK.format(st.troughAt()));
            o.put("maeWon", st.troughPrice());
            o.put("pattern", st.pattern());
            Files.writeString(storeFile, HttpJson.MAPPER.writeValueAsString(o) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("주가 통계 저장 실패: {} - {}", d.corpName(), d.reportNm(), e);
        }
    }

    /**
     * 오름차순 분봉 목록에서 target 시각 이하(≤)의 가장 늦은 봉을 고른다 — "공시 2분 전 가격".
     * target 이전 봉이 하나도 없으면(장 초반 등) 가장 이른 봉으로 폴백한다. 목록이 비면 null.
     * (네트워크 분리 — 테스트용 패키지 가시성)
     */
    static MinuteCandle pickAtOrBefore(List<MinuteCandle> candles, LocalTime target) {
        MinuteCandle picked = null;
        for (MinuteCandle c : candles) {
            if (!c.time().isAfter(target)) picked = c;   // 오름차순이라 ≤target 중 마지막이 가장 늦은 봉
        }
        if (picked == null && !candles.isEmpty()) picked = candles.get(0);   // target 이전 봉이 없으면 가장 이른 봉
        return picked;
    }

    /**
     * 공시 직전 가격 한 줄 + 100만원 매수 가늠 — 콤팩트하게 "{원} ({±등락률%})\n💰 100만원 ≈ {N}주".
     * (회사명·시각·"가격" 라벨은 뺀다 — 회사명은 바로 위 공시 헤더에 있다.) 전일종가가 0이면 % 생략.
     * (순수 함수 — 테스트용 패키지 가시성)
     */
    static String composeEntryPrice(MinuteCandle base, long prdyClose) {
        long price = base.close();
        String head = prdyClose > 0
                ? String.format("%,d원 (%+.1f%%)", price, (price - prdyClose) * 100.0 / prdyClose)
                : String.format("%,d원", price);
        return head + "\n" + budgetShares(price);
    }

    /**
     * 100만원으로 살 수 있는 주식 수 한 줄 — "💰 100만원 ≈ 28주"(100만÷가격, 내림).
     * 1주가 100만원을 넘으면 살 수 없으므로 1주 가격을 안내한다. (순수 함수 — 테스트용)
     */
    static String budgetShares(long price) {
        if (price <= 0) return "💰 100만원 ≈ -";
        long shares = BUDGET_WON / price;
        return shares > 0
                ? String.format("💰 100만원 ≈ %,d주", shares)
                : String.format("💰 1주 %,d원 (100만원 초과)", price);
    }

    /**
     * 시작가(공시 {@value #PRE_MIN}분 전 종가) 대비 10분 뒤 종료가의 순방향 — "상승/하락/보합".
     * 패턴 임계(±{@value Stats#FLAT_EPS_PCT}%) 안이면 "보합". 모양 패턴("올랐다 내림" 등)만으로는
     * 순방향이 안 보여, 이 값을 패턴 앞에 붙여 "시작가보다 결국 올랐나 내렸나"를 먼저 알린다.
     * (순수 함수 — 테스트용 패키지 가시성)
     */
    static String startDirection(long discPrice, long endPrice) {
        double p = discPrice > 0 ? (endPrice - discPrice) * 100.0 / discPrice : 0.0;
        if (p >= Stats.FLAT_EPS_PCT) return "상승";
        if (p <= -Stats.FLAT_EPS_PCT) return "하락";
        return "보합";
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private boolean withinWindow(ZonedDateTime now) {
        if (!calendar.isTradingDay(now.toLocalDate())) return false;   // 주말·공휴일
        return TradingSession.withinExtendedHours(now.toLocalTime());
    }
}
