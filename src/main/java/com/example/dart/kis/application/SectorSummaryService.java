package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.kis.domain.Gainer;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.domain.VolumeRankItem;
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
 * 급등 종목 섹터 요약 — 그 시점 라이브 급등 종목을 조회해 업종별로 집계·발송한다.
 * 등락률은 누적이 아니라 호출 시점 실시간 값이다. 업종명만 캐시(정적)를 쓴다.
 */
public class SectorSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SectorSummaryService.class);

    private final KisClient client;
    private final SectorCacheStore sectors;
    private final Notifier notifier;
    private final double minChangePct;

    public SectorSummaryService(KisClient client, SectorCacheStore sectors, Notifier notifier, double minChangePct) {
        this.client = client;
        this.sectors = sectors;
        this.notifier = notifier;
        this.minChangePct = minChangePct;
    }

    /** 섹터 요약 1건을 발송한다. 급등 종목이 없으면 건너뛴다. */
    public void send(Session session, String label, LocalTime time) {
        String msg = build(session, label, time);
        if (msg == null) return;
        try {
            notifier.send(msg);
        } catch (Exception e) {
            log.warn("섹터 요약 알림 전송 실패", e);
        }
    }

    /**
     * 라이브 급등 종목을 업종별로 집계한 섹터 요약 문자열을 만든다(전송은 호출부 책임).
     * 급등 종목이 없거나 라이브 조회 실패면 null. 섹터 요약 발송과 장 흐름 리포트가 공통으로 쓴다.
     */
    public String build(Session session, String label, LocalTime time) {
        List<VolumeRankItem> snapshot;
        try {
            snapshot = new ArrayList<>(client.topGainers(session.marketDiv));
        } catch (Exception e) {
            log.warn("섹터 요약 라이브 조회 실패 ({}): {}", label, e.toString());
            return null;
        }
        snapshot.removeIf(it -> !GainerScout.isBigGainer(it, minChangePct));  // 현재 ≥임계인 종목만
        if (snapshot.isEmpty()) {
            log.info("섹터 요약 건너뜀 — 급등 종목 없음 ({})", label);
            return null;
        }
        Map<String, String> sectorByCode = sectors.resolve(
                snapshot.stream().map(VolumeRankItem::code).toList());
        List<Gainer> resolved = new ArrayList<>(snapshot.size());
        int classified = 0;
        for (VolumeRankItem it : snapshot) {
            String s = sectorByCode.getOrDefault(it.code(), SectorCacheStore.UNCLASSIFIED);
            if (!SectorCacheStore.UNCLASSIFIED.equals(s)) classified++;
            resolved.add(new Gainer(it.name(), s, it.changePct()));
        }
        log.info("섹터 요약 조립 ({}종목, 업종분류 {}/{}, {})",
                resolved.size(), classified, resolved.size(), label);
        if (classified == 0) {
            log.warn("섹터 업종 조회가 전부 실패 — KIS 업종(inquire-price) 응답/권한·유량제한 확인 필요");
        }
        return compose(resolved, label, time);
    }

    /**
     * 급등 종목들의 업종 분포를 종목 수 비율 내림차순으로 정렬해 요약 메시지로 만든다.
     * 업종별로 비율(해당 업종 종목 수 / 전체 급등 종목 수)과 함께 소속 종목·등락률(% 내림차순)을 나열한다.
     * 상위 10개 업종까지 표시. (순수 함수 — 테스트용)
     */
    static String compose(Collection<Gainer> gainers, String session, LocalTime time) {
        int total = gainers.size();
        Map<String, List<Gainer>> bySector = new HashMap<>();
        for (Gainer g : gainers) {
            bySector.computeIfAbsent(g.sector(), k -> new ArrayList<>()).add(g);
        }
        List<Map.Entry<String, List<Gainer>>> ranked = new ArrayList<>(bySector.entrySet());
        ranked.sort(Comparator
                .comparingInt((Map.Entry<String, List<Gainer>> e) -> e.getValue().size()).reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **섹터 요약** | %s %s%n급등 %d종목 기준",
                session, KstTime.HH_MM.format(time), total));
        int rank = 1;
        for (Map.Entry<String, List<Gainer>> e : ranked) {
            List<Gainer> list = e.getValue();
            list.sort(Comparator.comparingDouble(Gainer::changePct).reversed());
            int pct = (int) Math.round(list.size() * 100.0 / total);
            String stocks = list.stream()
                    .map(g -> String.format("%s %+.1f%%", g.name(), g.changePct()))
                    .collect(Collectors.joining(", "));
            sb.append(String.format("%n%d. %s  %d%% (%d종목)%n   %s",
                    rank++, e.getKey(), pct, list.size(), stocks));
            if (rank > 10) break;
        }
        return sb.toString();
    }
}
