package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.kis.domain.KisMoney;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.domain.TradingValueItem;
import com.example.dart.kis.domain.Turnover;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.kis.infra.SectorCacheStore;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 거래대금 섹터 랭킹 — 거래대금 상위 종목을 라이브 조회해 업종별 거래대금을 합산,
 * "지금 어느 섹터가 활발한가"를 내림차순 랭킹 1건으로 발송한다(급등 섹터 요약과 별도 메시지).
 * 급등 종목이 없어도 거래대금은 장중 항상 존재하므로 독립적으로 동작한다.
 */
public class TurnoverRankingService {

    private static final Logger log = LoggerFactory.getLogger(TurnoverRankingService.class);

    /** 거래대금 섹터 랭킹에서 보여줄 상위 섹터 수. */
    private static final int TOP_SECTORS = 10;
    /** 각 섹터 안에서 보여줄 대표 종목 수(거래대금 상위). */
    private static final int STOCKS_PER_SECTOR = 3;

    private final KisClient client;
    private final SectorCacheStore sectors;
    private final Notifier notifier;

    public TurnoverRankingService(KisClient client, SectorCacheStore sectors, Notifier notifier) {
        this.client = client;
        this.sectors = sectors;
        this.notifier = notifier;
    }

    /** 거래대금 섹터 랭킹 1건을 발송한다. 종목이 없으면 건너뛴다. */
    public void send(Session session, String label, LocalTime time) {
        String msg = build(session, label, time);
        if (msg == null) return;
        notifier.trySend(msg, "거래대금 랭킹 알림 전송 실패");
    }

    /**
     * 거래대금 상위 종목을 업종별로 합산한 거래대금 섹터 랭킹 문자열을 만든다(전송은 호출부 책임).
     * 종목이 없거나 라이브 조회 실패면 null. 거래대금 랭킹 발송과 장 흐름 리포트가 공통으로 쓴다.
     */
    public String build(Session session, String label, LocalTime time) {
        List<TradingValueItem> snapshot;
        try {
            snapshot = client.topByTradingValue(session.marketDiv);
        } catch (Exception e) {
            log.warn("거래대금 랭킹 라이브 조회 실패 ({}): {}", label, e.toString());
            return null;
        }
        if (snapshot.isEmpty()) {
            log.info("거래대금 랭킹 건너뜀 — 종목 없음 ({})", label);
            return null;
        }
        Map<String, String> sectorByCode = sectors.resolve(
                snapshot.stream().map(TradingValueItem::code).toList());
        List<Turnover> resolved = new ArrayList<>(snapshot.size());
        for (TradingValueItem it : snapshot) {
            resolved.add(new Turnover(it.name(),
                    sectorByCode.getOrDefault(it.code(), SectorCacheStore.UNCLASSIFIED), it.tradingValueWon()));
        }
        log.info("거래대금 랭킹 조립 ({}종목, {})", resolved.size(), label);
        return compose(resolved, label, time);
    }

    /**
     * 거래대금 상위 종목들을 업종별 거래대금 합계 내림차순으로 정렬해 섹터 랭킹 메시지로 만든다.
     * 상위 {@value #TOP_SECTORS}개 섹터까지, 각 섹터 안 대표 종목 {@value #STOCKS_PER_SECTOR}개를
     * 거래대금 내림차순으로 나열한다. 비중(%)은 표본 전체 거래대금 대비 해당 섹터 비율. (순수 함수 — 테스트용)
     */
    static String compose(Collection<Turnover> items, String session, LocalTime time) {
        long total = items.stream().mapToLong(Turnover::valueWon).sum();
        Map<String, List<Turnover>> bySector = new HashMap<>();
        for (Turnover t : items) {
            bySector.computeIfAbsent(t.sector(), k -> new ArrayList<>()).add(t);
        }
        // 섹터별 거래대금 합계로 내림차순, 동률은 업종명 사전순.
        List<Map.Entry<String, List<Turnover>>> ranked = new ArrayList<>(bySector.entrySet());
        ranked.sort(Comparator
                .comparingLong((Map.Entry<String, List<Turnover>> e) ->
                        e.getValue().stream().mapToLong(Turnover::valueWon).sum()).reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("💰 **거래대금 섹터 랭킹** | %s %s%n(거래대금 상위 %d종목 기준)",
                session, KstTime.HH_MM.format(time), items.size()));
        int rank = 1;
        for (Map.Entry<String, List<Turnover>> e : ranked) {
            List<Turnover> list = e.getValue();
            list.sort(Comparator.comparingLong(Turnover::valueWon).reversed());
            long sectorSum = list.stream().mapToLong(Turnover::valueWon).sum();
            int pct = total > 0 ? (int) Math.round(sectorSum * 100.0 / total) : 0;
            String stocks = list.stream()
                    .limit(STOCKS_PER_SECTOR)
                    .map(t -> String.format("%s %s", t.name(), KisMoney.formatWon(t.valueWon())))
                    .collect(Collectors.joining(", "));
            sb.append(String.format("%n%d. %s  %s (%d%%)%n   %s",
                    rank++, e.getKey(), KisMoney.formatWon(sectorSum), pct, stocks));
            if (rank > TOP_SECTORS) break;
        }
        return sb.toString();
    }
}
