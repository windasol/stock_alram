package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.common.text.TextTable;
import com.example.dart.kis.domain.FlowPhase;
import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.InvestorConfirmed;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.KisMoney;
import com.example.dart.kis.domain.MarketInvestorFlow;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.infra.DomesticMarketClient;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 외국인·기관 수급 랭킹 — 시각에 따라 3단계로 발송한다(FlowPhase 참고):
 * 정규장 추정 폴링(외국인=외국계 실시간, 기관·동시매매=거래소 가집계) → KRX 확정 1회 → NXT 최종 확정 1회.
 * 시장 전체(코스피·코스닥) 수급 헤드라인(네이버 소스)도 여기서 담당하고, 최신 스냅샷은 시황 리포트가 읽는다.
 */
public class InvestorFlowService {

    private static final Logger log = LoggerFactory.getLogger(InvestorFlowService.class);
    private static final ZoneId KST = KstTime.ZONE;
    private static final DateTimeFormatter SUMMARY_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** 외국인·기관 수급 랭킹에서 순매수/순매도 각각 보여줄 종목 수. */
    static final int INVESTOR_FLOW_TOP = 30;
    /** 외국인+기관 동시매매(양매수/양매도)에서 각각 보여줄 종목 수. */
    static final int INVESTOR_PAIR_TOP = 30;
    /** 수급 표 종목명 칸 표시폭(한글=2). "SK하이닉스"·"LG에너지솔루션" 등 대부분 수용. */
    private static final int FLOW_NAME_W = 12;
    /** 수급 표 순매수금액 칸 표시폭(우측정렬). "+1,234억"·"-987억" 등 수용. */
    private static final int FLOW_AMT_W = 9;
    /** 동시매매 표 외국인/기관 금액 칸 표시폭(우측정렬). */
    private static final int PAIR_AMT_W = 10;
    /** 개인 투자자 표시 라벨 — Investor enum(외국인·기관)엔 없어 시장 전체 수급용으로 별도 정의. */
    private static final String INDIVIDUAL_LABEL = "👤 개인";

    private final KisClient client;
    private final Notifier notifier;
    private final DomesticMarketClient domesticMarket;
    /** 거래일 판정(주말·공휴일 게이트). */
    private final MarketCalendar calendar;
    /** 수급 랭킹 활성 여부(주기>0). 비활성이면 tick이 발송하지 않는다. */
    private final boolean enabled;

    /** KRX 정규장 확정 수급을 발송한 날짜(KST). 당일 재발송 방지 — 단일 폴러 스레드 접근이라 동기화 불필요. */
    private LocalDate krxConfirmedSentDate = null;
    /** NXT 최종 확정 수급을 발송한 날짜(KST). 당일 재발송 방지 — 단일 폴러 스레드 접근이라 동기화 불필요. */
    private LocalDate nxtConfirmedSentDate = null;
    /** 최신 시장 수급 스냅샷(코스피·코스닥) — 틱이 갱신하고 시황 리포트가 읽는다. */
    private volatile List<MarketInvestorFlow> lastMarketFlows = List.of();

    public InvestorFlowService(KisClient client, Notifier notifier, DomesticMarketClient domesticMarket,
                               MarketCalendar calendar, boolean enabled) {
        this.client = client;
        this.notifier = notifier;
        this.domesticMarket = domesticMarket;
        this.calendar = calendar;
        this.enabled = enabled;
    }

    /**
     * 주기 틱(기본 10분) — 운영시간 내내 외국인·기관 수급 랭킹을 각각 1건씩 발송한다.
     * 단, 가집계 엔드포인트는 고정 시장코드(V)·정규장 증권사 입력치(마지막 14:30)만 반영하므로
     * NXT 애프터마켓엔 값이 갱신되지 않고 그날 최종 스냅샷이 반복된다(세션 라벨로 애프터마켓임을 표시).
     */
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!calendar.isTradingDay(now.toLocalDate())) return;   // 주말·공휴일 제외
        LocalTime time = now.toLocalTime();
        LocalDate today = now.toLocalDate();
        FlowPhase phase = FlowPhase.at(time);
        if (phase == null) return;   // 장 시작 전

        switch (phase) {
            case NXT_CONFIRMED -> {
                // NXT 최종 확정(20:05~) — 외국인·기관 확정 1회(NX). 당일 1회.
                if (today.equals(nxtConfirmedSentDate)) return;
                if (sendConfirmedInvestorFlow(Session.NXT_AFTER, time)) nxtConfirmedSentDate = today;
            }
            case KRX_CONFIRMED -> {
                // (a) 정규장 확정(15:35~) — 당일 1회. 확정이 아직 안 잡히면 false → 다음 틱 재시도.
                if (!today.equals(krxConfirmedSentDate) && sendConfirmedInvestorFlow(Session.REGULAR, time)) {
                    krxConfirmedSentDate = today;
                }
                // (b) NXT 외국인 수급 — 외국계 실시간(NX)으로 매 틱 폴링. NX 미지원/장외면 빈 응답 → 자동 스킵.
                //     (NXT는 기관 실시간 소스가 없어 외국인만 라이브로 본다.)
                sendForeignEstimateTable("NX", Session.NXT_AFTER.label, time);
            }
            case ESTIMATE -> sendEstimateFlow(time);   // 정규장 추정 10분 폴링(표 + 분석)
        }
    }

    /**
     * 시장 전체(코스피·코스닥) 외국인·기관 순매수 헤드라인을 주기로 KIS 채널에 보낸다(정규장·평일만).
     * 종목별 랭킹과 별개로 "시장 전체로 누가 사고/파는지"를 한눈에. 최신 스냅샷은 시황 리포트도 읽는다.
     *
     * 소스는 네이버 실시간 지수 투자자 트렌드(코스피/코스닥 분리, 개인·외국인·기관) — KIS 오픈API가 코스피만 못 줘 값이
     * 계속 틀렸던 것을 대체한다. 정규장(09:00~15:40)에만 발송한다(장중 헤드라인 성격 유지).
     */
    public void marketFlowTick() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!calendar.isTradingDay(now.toLocalDate())
                || Session.at(now.toLocalTime()) != Session.REGULAR) return;   // 장외·주말·NXT 애프터마켓이면 스킵
        LocalTime time = now.toLocalTime();
        List<MarketInvestorFlow> flows = fetchMarketFlows();
        if (flows.isEmpty()) return;   // 조회 실패(거부·빈응답) — 채널엔 안 보냄(로그만)
        lastMarketFlows = flows;       // 시황 리포트가 읽도록 저장
        String indexLine = domesticMarket.indexHeadlineLine();   // 현재 코스피·코스닥(전일 대비, null 가능)
        try {
            notifier.send(composeMarketFlow(flows, time, "잠정", indexLine));   // 급등·수급표와 같은 KIS 채널
        } catch (Exception e) {
            log.warn("시장 전체 수급 헤드라인 전송 실패", e);
        }
    }

    /** 최신 시장 수급 스냅샷 — 틱이 저장한 것을 쓰되, 없으면(헤드라인 비활성 등) 즉석 조회. 시황 리포트용. */
    public List<MarketInvestorFlow> latestMarketFlows() {
        return lastMarketFlows.isEmpty() ? fetchMarketFlows() : lastMarketFlows;
    }

    /**
     * 코스피·코스닥 시장 전체 수급(개인·외국인·기관)을 네이버 실시간 지수 트렌드에서 가져온다.
     * KIS 오픈API(FHPTJ04030000)는 코스피 코드(0001)가 전부 0이라 전체(999)로 폴백돼 값이 계속 틀렸다 —
     * 네이버는 코스피/코스닥을 정확히 분리해 준다({@link DomesticMarketClient#investorFlows()}). 전부 실패면 빈 목록.
     */
    private List<MarketInvestorFlow> fetchMarketFlows() {
        return domesticMarket.investorFlows().stream()
                .map(n -> new MarketInvestorFlow(n.market(), n.foreignWon(), n.institutionWon(), n.individualWon()))
                .toList();
    }

    /**
     * 정규장 추정 구간 — 외국인은 외국계 실시간(자주 갱신), 기관·동시매매는 거래소 가집계(4회/일)로 조회해
     * 표를 발송한다. 외국인을 외국계 실시간으로 바꿔 장중 수급 변화가 키움 실시간 외국인 수급처럼 자주 반영되게 한다.
     */
    private void sendEstimateFlow(LocalTime time) {
        Session sess = Session.REGULAR;   // 추정 구간은 항상 정규장 라벨(가집계는 시장코드 V 전용)
        // 외국인 — 외국계 실시간(정규장 J). 거래소 가집계 대신 사용해 더 자주 갱신.
        List<InvestorFlowItem> frgnBuys = client.foreignMemberEstimate("J", true);
        List<InvestorFlowItem> frgnSells = client.foreignMemberEstimate("J", false);
        // 기관 — 외국계 창구 구분이 안 돼 실시간 소스가 없으므로 거래소 가집계 유지.
        List<InvestorFlowItem> instBuys = client.investorFlowRank(Investor.INSTITUTION, true);
        List<InvestorFlowItem> instSells = client.investorFlowRank(Investor.INSTITUTION, false);
        List<InvestorPairItem> dualBuy = dualBuyTop(client.investorFlowDual(true), INVESTOR_PAIR_TOP);
        List<InvestorPairItem> dualSell = dualSellTop(client.investorFlowDual(false), INVESTOR_PAIR_TOP);

        String indexLine = domesticMarket.indexHeadlineLine();   // 현재 코스피·코스닥(전일 대비, null 가능) — 표에 같이 표시
        sendFlowTable(Investor.FOREIGN, frgnBuys, frgnSells, sess.label, time, "외국계 실시간", indexLine);
        sendFlowTable(Investor.INSTITUTION, instBuys, instSells, sess.label, time, "가집계·추정", indexLine);
        sendPairTable(dualBuy, dualSell, sess.label, time);
        // LLM 분석은 여기서 하지 않는다 — 시간당 MacroReportService가 뉴스 채널로 별도 발송.
    }

    /** 한 투자자의 순매수·순매도 상위를 각각 TOP{@value #INVESTOR_FLOW_TOP}로 잘라 표 1건 발송. 둘 다 비면 미발송. */
    private void sendFlowTable(Investor inv, List<InvestorFlowItem> buys, List<InvestorFlowItem> sells,
                               String label, LocalTime time, String tag, String indexLine) {
        if (!enabled) return;
        if (buys.isEmpty() && sells.isEmpty()) {
            log.info("{} 수급 랭킹 건너뜀 — 데이터 없음 ({} {})", tag, inv, label);
            return;
        }
        List<InvestorFlowItem> topBuys = buys.stream().limit(INVESTOR_FLOW_TOP).toList();
        List<InvestorFlowItem> topSells = sells.stream().limit(INVESTOR_FLOW_TOP).toList();
        log.info("{} 수급 랭킹 조립 ({} 매수 {}종목·매도 {}종목, {})",
                tag, inv, topBuys.size(), topSells.size(), label);
        try {
            notifier.send(composeInvestorFlow(inv, topBuys, topSells, label, time, tag, indexLine));
        } catch (Exception e) {
            log.warn("수급 랭킹 알림 전송 실패 ({} {})", tag, inv, e);
        }
    }

    /**
     * 외국계 실시간(외국인) 수급 표 1건 발송 — 정규장은 marketDiv="J", NXT는 "NX".
     * 거래소 가집계(4회/일)와 달리 장중 더 자주 갱신되는 외국계 창구 추정(키움 실시간 외국인 수급 계열).
     * NX는 KIS 문서 미명시라 미지원/장외면 빈 응답 → 자동 스킵(되면 표 발송, 안 되면 발송 없음).
     */
    private void sendForeignEstimateTable(String marketDiv, String label, LocalTime time) {
        if (!enabled) return;
        List<InvestorFlowItem> buys = client.foreignMemberEstimate(marketDiv, true);
        List<InvestorFlowItem> sells = client.foreignMemberEstimate(marketDiv, false);
        String indexLine = domesticMarket.indexHeadlineLine();   // 현재 코스피·코스닥(전일 대비, null 가능)
        sendFlowTable(Investor.FOREIGN, buys, sells, label, time, "외국계 실시간", indexLine);
    }

    /** 양매수·양매도(이미 필터·정렬된) 종목을 동시매매 표 1건으로 발송. 둘 다 비면 미발송. */
    private void sendPairTable(List<InvestorPairItem> dualBuy, List<InvestorPairItem> dualSell,
                               String label, LocalTime time) {
        if (!enabled) return;
        if (dualBuy.isEmpty() && dualSell.isEmpty()) {
            log.info("동시매매 랭킹 건너뜀 — 해당 종목 없음 ({})", label);
            return;
        }
        log.info("동시매매 랭킹 조립 (양매수 {}종목·양매도 {}종목, {})", dualBuy.size(), dualSell.size(), label);
        try {
            notifier.send(composeInvestorPair(dualBuy, dualSell, label, time, "가집계·추정"));
        } catch (Exception e) {
            log.warn("동시매매 랭킹 알림 전송 실패", e);
        }
    }

    /**
     * 마감 후 '확정' 수급 — 가집계 TOP 후보 종목을 inquire-investor 확정치로 재조회·재정렬해 외국인·기관·동시매매를 발송한다.
     * inquire-investor는 1회 호출로 외국인·기관 확정값을 함께 주므로, 후보 종목 합집합을 1회씩만 조회해 3개 메시지를 모두 만든다.
     * (전체 시장 확정 랭킹 엔드포인트는 KIS에 없어, 가집계가 띄운 종목으로 후보를 한정한다 — 가집계 미포착 종목은 누락될 수 있음.)
     *
     * @return 1건이라도 발송했으면 true(그 날 재발송 방지용).
     */
    private boolean sendConfirmedInvestorFlow(Session sess, LocalTime time) {
        if (!enabled) return false;
        String label = sess.label;

        // 1) 가집계로 후보 종목(코드→종목명) 수집 — 외국인/기관 순매수·순매도 + 동시매매(ETC=0).
        Map<String, String> names = new HashMap<>();
        for (List<InvestorFlowItem> lst : List.of(
                client.investorFlowRank(Investor.FOREIGN, true),
                client.investorFlowRank(Investor.FOREIGN, false),
                client.investorFlowRank(Investor.INSTITUTION, true),
                client.investorFlowRank(Investor.INSTITUTION, false))) {
            for (InvestorFlowItem it : lst) names.putIfAbsent(it.code(), it.name());
        }
        for (List<InvestorPairItem> lst : List.of(client.investorFlowDual(true), client.investorFlowDual(false))) {
            for (InvestorPairItem it : lst) names.putIfAbsent(it.code(), it.name());
        }
        if (names.isEmpty()) {
            log.info("확정 수급 건너뜀 — 가집계 후보 종목 없음 ({})", label);
            return false;
        }

        // 2) 후보 종목 합집합을 inquire-investor로 1회씩 확정 조회(외국인·기관 동시 반환).
        //    시장구분은 현재 시각으로 판단한다 — 20:05 이후=NXT 기반(NX), 그 전=KRX 확정(J).
        String marketDiv = FlowPhase.confirmedMarketDiv(time);
        Map<String, InvestorConfirmed> confirmed = new HashMap<>();
        for (String code : names.keySet()) {
            InvestorConfirmed c = client.inquireInvestorConfirmed(code, marketDiv);
            if (c != null) confirmed.put(code, c);
        }
        if (confirmed.isEmpty()) {
            log.warn("확정 수급 조회 전부 실패 — 발송 보류 ({})", label);
            return false;
        }
        String tag = confirmedDateTag(confirmed);   // "확정 06/25"
        log.info("확정 수급 조립 ({}개 후보 중 {}종목 확정, {})", names.size(), confirmed.size(), label);

        // 3) 외국인·기관 각각 확정값으로 재정렬해 발송, 동시매매도 확정값으로 발송.
        String indexLine = domesticMarket.indexHeadlineLine();   // 종가 기준 코스피·코스닥(전일 대비, null 가능)
        boolean sent = false;
        sent |= sendConfirmedFlow(Investor.FOREIGN, names, confirmed, label, time, tag, indexLine);
        sent |= sendConfirmedFlow(Investor.INSTITUTION, names, confirmed, label, time, tag, indexLine);
        sent |= sendConfirmedPair(names, confirmed, label, time, tag);
        return sent;
    }

    /** 한 투자자의 확정 순매수/순매도 상위를 재정렬해 1건 발송한다. 둘 다 비면 미발송(false). */
    private boolean sendConfirmedFlow(Investor inv, Map<String, String> names,
                                      Map<String, InvestorConfirmed> confirmed, String label, LocalTime time, String tag,
                                      String indexLine) {
        List<InvestorFlowItem> items = confirmed.entrySet().stream()
                .map(e -> new InvestorFlowItem(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().netWon(inv), 0.0))
                .toList();
        List<InvestorFlowItem> buys = items.stream().filter(it -> it.netValueWon() > 0)
                .sorted(Comparator.comparingLong(InvestorFlowItem::netValueWon).reversed())
                .limit(INVESTOR_FLOW_TOP).toList();
        List<InvestorFlowItem> sells = items.stream().filter(it -> it.netValueWon() < 0)
                .sorted(Comparator.comparingLong(InvestorFlowItem::netValueWon))
                .limit(INVESTOR_FLOW_TOP).toList();
        if (buys.isEmpty() && sells.isEmpty()) return false;
        try {
            notifier.send(composeInvestorFlow(inv, buys, sells, label, time, tag, indexLine));
            return true;
        } catch (Exception e) {
            log.warn("확정 수급 랭킹 알림 전송 실패 ({})", inv, e);
            return false;
        }
    }

    /** 외국인·기관 둘 다 순매수(양매수)인 종목을 합산액(sumWon) 큰 순으로 상위 n개. */
    static List<InvestorPairItem> dualBuyTop(List<InvestorPairItem> items, int n) {
        return items.stream()
                .filter(it -> it.frgnWon() > 0 && it.orgnWon() > 0)
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon).reversed())
                .limit(n).toList();
    }

    /** 외국인·기관 둘 다 순매도(양매도)인 종목을 합산액 작은(순매도 큰) 순으로 상위 n개. */
    static List<InvestorPairItem> dualSellTop(List<InvestorPairItem> items, int n) {
        return items.stream()
                .filter(it -> it.frgnWon() < 0 && it.orgnWon() < 0)
                .sorted(Comparator.comparingLong(InvestorPairItem::sumWon))
                .limit(n).toList();
    }

    /** 외국인·기관 둘 다 순매수(양매수)·둘 다 순매도(양매도)인 종목을 확정값으로 추려 1건 발송한다. 둘 다 비면 미발송. */
    private boolean sendConfirmedPair(Map<String, String> names, Map<String, InvestorConfirmed> confirmed,
                                      String label, LocalTime time, String tag) {
        List<InvestorPairItem> items = confirmed.entrySet().stream()
                .map(e -> new InvestorPairItem(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().foreignWon(), e.getValue().institutionWon(), 0.0))
                .toList();
        List<InvestorPairItem> dualBuy = dualBuyTop(items, INVESTOR_PAIR_TOP);
        List<InvestorPairItem> dualSell = dualSellTop(items, INVESTOR_PAIR_TOP);
        if (dualBuy.isEmpty() && dualSell.isEmpty()) return false;
        try {
            notifier.send(composeInvestorPair(dualBuy, dualSell, label, time, tag));
            return true;
        } catch (Exception e) {
            log.warn("확정 동시매매 알림 전송 실패", e);
            return false;
        }
    }

    /** 확정 조회 결과의 거래일자(YYYYMMDD)로 "확정 MM/DD" 태그를 만든다. 날짜 미상이면 "확정". */
    private static String confirmedDateTag(Map<String, InvestorConfirmed> confirmed) {
        for (InvestorConfirmed c : confirmed.values()) {
            String d = c.date();
            if (d != null && d.length() == 8) {
                return "확정 " + d.substring(4, 6) + "/" + d.substring(6, 8);
            }
        }
        return "확정";
    }

    /**
     * 시장 전체(코스피·코스닥) 외국인·기관·개인 순매수 헤드라인 메시지. (순수 함수 — 테스트용)
     * 예: "📊 **시장 수급** | 13:40 (가집계)\n🌍 외국인 -3,200억 · 🏛 기관 +1,500억 · 👤 개인 +1,700억".
     */
    static String composeMarketFlow(List<MarketInvestorFlow> flows, LocalTime time, String tag) {
        return composeMarketFlow(flows, time, tag, null);
    }

    /**
     * 시장 수급 헤드라인. {@code indexLine}이 있으면 제목 줄 아래에 현재 지수(코스피·코스닥) 한 줄을 덧붙여
     * '지수(결과) → 누가 사고파나(수급)'를 한눈에 보이게 한다. null이면 지수 줄 생략.
     */
    static String composeMarketFlow(List<MarketInvestorFlow> flows, LocalTime time, String tag, String indexLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **시장 수급** | %s  (%s)", SUMMARY_TIME_FMT.format(time), tag));
        if (indexLine != null) sb.append(String.format("%n%s", indexLine));
        for (MarketInvestorFlow f : flows) {
            String prefix = f.market().isBlank() ? "" : f.market() + "  ";   // 전체(빈 라벨)면 접두어 없음
            sb.append(String.format("%n%s%s %s · %s %s · %s %s",
                    prefix,
                    Investor.FOREIGN.label(), KisMoney.formatNetWon(f.foreignNetWon()),
                    Investor.INSTITUTION.label(), KisMoney.formatNetWon(f.institutionNetWon()),
                    INDIVIDUAL_LABEL, KisMoney.formatNetWon(f.individualNetWon())));
        }
        return sb.toString();
    }

    /**
     * 시황 리포트 facts용 시장 전체 수급 한 줄(컴팩트). 비면 null.
     * 예: "📊 시장 수급 | 외국인 -3,200억·기관 +1,500억·개인 +1,700억". (순수 함수 — 테스트용)
     */
    static String marketFlowLine(List<MarketInvestorFlow> flows) {
        if (flows.isEmpty()) return null;
        String body = flows.stream()
                .map(f -> {
                    String prefix = f.market().isBlank() ? "" : f.market() + " ";   // 전체(빈 라벨)면 접두어 없음
                    return String.format("%s외국인 %s·기관 %s·개인 %s",
                            prefix, KisMoney.formatNetWon(f.foreignNetWon()), KisMoney.formatNetWon(f.institutionNetWon()),
                            KisMoney.formatNetWon(f.individualNetWon()));
                })
                .collect(Collectors.joining(" / "));
        return "📊 시장 수급 | " + body;
    }

    static String composeInvestorFlow(Investor inv, List<InvestorFlowItem> buys,
                                      List<InvestorFlowItem> sells, String session, LocalTime time, String tag) {
        return composeInvestorFlow(inv, buys, sells, session, time, tag, null);
    }

    /**
     * 한 투자자의 순매수 상위(가장 많이 산)·순매도 상위(가장 많이 판)를 좌우 2열로 나란히 둔 표를 만든다.
     * Webex 마크다운은 표(| |)를 렌더하지 않으므로 코드블록(고정폭) 안에 한글 표시폭을 맞춰 ASCII로 정렬한다.
     * 셀은 종목명 + 순매수금액(컴팩트, 등락률 생략). 같은 순위 행에 매수·매도가 함께 온다.
     * {@code indexLine}이 있으면 제목 줄 아래에 현재 지수 헤드라인을 한 줄 덧붙인다. (순수 함수 — 테스트용)
     */
    static String composeInvestorFlow(Investor inv, List<InvestorFlowItem> buys,
                                      List<InvestorFlowItem> sells, String session, LocalTime time, String tag,
                                      String indexLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s **수급 TOP%d** | %s %s  (%s)",
                inv.label(), INVESTOR_FLOW_TOP, session, SUMMARY_TIME_FMT.format(time), tag));
        if (indexLine != null) sb.append(String.format("%n%s", indexLine));

        int rows = Math.max(buys.size(), sells.size());
        if (rows == 0) {
            sb.append(String.format("%n```%n(데이터 없음)%n```"));
            return sb.toString();
        }

        int cellW = FLOW_NAME_W + 1 + FLOW_AMT_W;   // 종목명 + 공백 + 금액
        sb.append(String.format("%n```"));
        // 헤더 + 구분선
        sb.append(String.format("%n %s %s %s",
                TextTable.padDisplay("#", 2, false),
                TextTable.padDisplay("매수(많이산)", cellW, true),
                TextTable.padDisplay("매도(많이판)", cellW, true)));
        sb.append(String.format("%n %s %s %s",
                "--", "-".repeat(cellW), "-".repeat(cellW)));
        for (int i = 0; i < rows; i++) {
            String buyCell = i < buys.size() ? flowCell(buys.get(i)) : " ".repeat(cellW);
            String sellCell = i < sells.size() ? flowCell(sells.get(i)) : "";
            sb.append(String.format("%n %s %s %s",
                    TextTable.padDisplay(Integer.toString(i + 1), 2, false), buyCell, sellCell));
        }
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 수급 표 한 칸 — "종목명(좌측정렬)  순매수금액(우측정렬)". */
    private static String flowCell(InvestorFlowItem it) {
        return TextTable.padDisplay(it.name(), FLOW_NAME_W, true)
                + " " + TextTable.padDisplay(KisMoney.formatNetWon(it.netValueWon()), FLOW_AMT_W, false);
    }

    /**
     * 양매수·양매도 종목을 종목 / 외국인 / 기관 3열 표(코드블록)로 정리한 메시지를 만든다. (순수 함수 — 테스트용)
     */
    static String composeInvestorPair(List<InvestorPairItem> dualBuy, List<InvestorPairItem> dualSell,
                                      String session, LocalTime time, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🤝 **외국인+기관 동시매매** | %s %s  (%s)",
                session, SUMMARY_TIME_FMT.format(time), tag));
        sb.append(String.format("%n```"));
        appendPairSection(sb, "[양매수 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매수", dualBuy);
        appendPairSection(sb, "[양매도 TOP" + INVESTOR_PAIR_TOP + "] 외국인·기관 둘 다 순매도", dualSell);
        sb.append(String.format("%n```"));
        return sb.toString();
    }

    /** 동시매매 한 섹션 — 헤더 + "순번 종목 외국인 기관" 표. 비면 안내 문구. */
    private static void appendPairSection(StringBuilder sb, String header, List<InvestorPairItem> items) {
        sb.append(String.format("%n%s", header));
        sb.append(String.format("%n %s %s %s %s",
                TextTable.padDisplay("#", 2, false),
                TextTable.padDisplay("종목", FLOW_NAME_W, true),
                TextTable.padDisplay("외국인", PAIR_AMT_W, false),
                TextTable.padDisplay("기관", PAIR_AMT_W, false)));
        if (items.isEmpty()) {
            sb.append(String.format("%n (해당 종목 없음)"));
            return;
        }
        int rank = 1;
        for (InvestorPairItem it : items) {
            sb.append(String.format("%n %s %s %s %s",
                    TextTable.padDisplay(Integer.toString(rank++), 2, false),
                    TextTable.padDisplay(it.name(), FLOW_NAME_W, true),
                    TextTable.padDisplay(KisMoney.formatNetWon(it.frgnWon()), PAIR_AMT_W, false),
                    TextTable.padDisplay(KisMoney.formatNetWon(it.orgnWon()), PAIR_AMT_W, false)));
        }
    }
}
