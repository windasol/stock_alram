package com.example.dart.kis.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목코드 → KRX 업종명 캐시(파일 영속) + 유량제한 회피 조회기.
 *
 * 업종은 거의 불변이라 한 번 조회하면 계속 재사용한다(디스크 영속화).
 * 모의 도메인 inquire-price는 유량제한이 빡빡해(≈초당 1~2건) 수십 건을 한꺼번에 조회하면 'EGW00201 초과'로
 * 빈 값→'미분류'가 되므로, 호출은 throttle하고 성공분은 캐시·파일에 모아 워밍업 1회 뒤엔 재조회를 없앤다.
 * (업종명은 정적이라 캐시해도 실시간 등락률과 무관 — 등락률은 요약 때마다 라이브로 새로 조회한다.)
 */
public class SectorCacheStore {

    private static final Logger log = LoggerFactory.getLogger(SectorCacheStore.class);

    /** 업종 미상 종목의 섹터 라벨. */
    public static final String UNCLASSIFIED = "미분류";
    /** 모의 도메인 inquire-price 유량제한 회피용 — 캐시 미스(실호출) 사이 최소 간격(ms). */
    private static final long SECTOR_LOOKUP_INTERVAL_MS = 1000L;

    private final KisClient client;
    /** 업종 캐시 영속화 파일 — 각 행: 코드\t업종명. 날짜 무관(업종은 안정적이라 계속 재사용). */
    private final Path sectorsFile;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SectorCacheStore(KisClient client, Path sectorsFile) {
        this.client = client;
        this.sectorsFile = sectorsFile;
        load();
    }

    /**
     * 종목코드들의 KRX 업종을 해소한다 — 캐시 우선, 미스만 throttle해서 라이브 조회 후 캐시·파일에 보존.
     * 조회 실패(빈 업종)는 캐시하지 않고(다음 주기 재시도) 결과 맵엔 '미분류'로 채운다.
     * 급등 요약·거래대금 랭킹·시황 리포트가 공통으로 쓴다(업종 캐시·유량제한 회피 로직 단일화).
     */
    public Map<String, String> resolve(List<String> codes) {
        Map<String, String> out = new HashMap<>();
        int newLookups = 0;
        for (String code : codes) {
            String s = cache.get(code);
            if (s == null || s.isBlank()) {
                if (newLookups++ > 0) sleepBetweenLookups();  // 실호출 사이만 간격
                s = client.sectorOf(code);
                if (s != null && !s.isBlank()) cache.put(code, s);  // 성공만 캐시
            }
            out.put(code, (s != null && !s.isBlank()) ? s : UNCLASSIFIED);
        }
        if (newLookups > 0) {
            persist();  // 이번에 새로 조회된 업종을 파일에 보존(워밍업 1회로 끝)
            log.info("업종 신규조회 {}건", newLookups);
        }
        return out;
    }

    /** 업종 캐시를 파일에 저장한다 — 각 행: 코드\t업종명. */
    private void persist() {
        try {
            List<String> lines = new ArrayList<>(cache.size());
            for (Map.Entry<String, String> e : cache.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
            Files.write(sectorsFile, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("KIS 업종 캐시 저장 실패: {}", e.toString());
        }
    }

    /** 시작 시 업종 캐시를 복원한다 — 워밍업(수십 건 조회) 비용을 매 재시작마다 다시 치르지 않게. */
    private void load() {
        try {
            if (!Files.exists(sectorsFile)) return;
            for (String line : Files.readAllLines(sectorsFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 2);
                if (p.length == 2 && !p[1].isBlank()) cache.put(p[0], p[1]);
            }
            log.info("KIS 업종 캐시 {}건 복원", cache.size());
        } catch (Exception e) {
            log.warn("KIS 업종 캐시 복원 실패 — 빈 상태로 시작: {}", e.toString());
        }
    }

    /** 업종 실호출 사이 간격 — 모의 도메인 유량제한(EGW00201) 회피. */
    private void sleepBetweenLookups() {
        try {
            Thread.sleep(SECTOR_LOOKUP_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
