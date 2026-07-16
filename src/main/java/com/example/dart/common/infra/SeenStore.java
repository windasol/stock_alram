package com.example.dart.common.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * 이미 처리한 항목 ID를 파일로 영속 저장 — 재시작해도 중복 알림을 막는다.
 *
 * <p>무한 증가를 막기 위해 삽입순서를 보존하는 {@link LinkedHashSet}로 최대 {@code maxEntries}개만 유지한다.
 * 상한을 {@link #COMPACT_SLACK}만큼 넘으면 가장 오래된 항목부터 잘라내고 파일을 통째 재기록해 크기를 되돌린다
 * (재기록 비용을 상한 초과 구간 전체에 분산). 접수번호는 날짜 프리픽스로 단조증가하고 뉴스 링크도 오래되면
 * 재등장하지 않으므로, 오래된 항목 제거가 중복 재알림을 유발할 가능성은 사실상 없다.
 *
 * <p>{@code add}/{@code contains}는 {@code synchronized}다 — 두 폴러(DART·KIND)가 공유 키 저장소를
 * 동시에 두드려도 {@code add}가 원자적으로 한쪽에만 {@code true}를 돌려줘 선착순 중복제거가 보장된다.
 */
public class SeenStore {

    private static final Logger log = LoggerFactory.getLogger(SeenStore.class);

    /** 기본 상한 — 접수번호 계열(일 수천 건)은 최근 수십 일을 넉넉히 덮는다. */
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    /** 상한을 이만큼 초과했을 때만 잘라내고 재기록한다 — 매 add 재기록을 피해 비용을 분산. */
    private static final int COMPACT_SLACK = 2_000;

    private final Path file;
    private final int maxEntries;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    public SeenStore() {
        this(Path.of("seen.txt"));
    }

    public SeenStore(Path file) {
        this(file, DEFAULT_MAX_ENTRIES);
    }

    public SeenStore(Path file, int maxEntries) {
        this.file = file;
        this.maxEntries = maxEntries;
        loadFromFile();
    }

    public synchronized boolean contains(String id) {
        return seen.contains(id);
    }

    /** @return 처음 본 ID였으면 true — 두 폴러가 같은 키를 두고 경쟁해도 한쪽만 true를 받는다. */
    public synchronized boolean add(String id) {
        if (!seen.add(id)) {
            return false;
        }
        if (seen.size() > maxEntries + COMPACT_SLACK) {
            compact();   // 오래된 항목 잘라내고 파일 통째 재기록
        } else {
            appendToFile(id);
        }
        return true;
    }

    private void loadFromFile() {
        if (!Files.exists(file)) return;
        try {
            Files.readAllLines(file).stream()
                    .filter(line -> !line.isBlank())
                    .forEach(seen::add);   // 파일 순서 = 삽입 순서(오래된 것이 앞)
            log.info("이전 처리 항목 {}건 로드 완료 ({})", seen.size(), file);
            if (seen.size() > maxEntries) {
                compact();   // 상한 초과 파일(구버전)이면 즉시 정리
            }
        } catch (IOException e) {
            log.warn("{} 로드 실패, 빈 상태로 시작", file, e);
        }
    }

    /** 상한(maxEntries)까지 가장 오래된 항목을 잘라낸 뒤 파일을 통째 다시 써 무한 증가를 막는다. */
    private void compact() {
        int removeCount = seen.size() - maxEntries;
        if (removeCount > 0) {
            Iterator<String> it = seen.iterator();
            for (int i = 0; i < removeCount && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        StringBuilder sb = new StringBuilder(seen.size() * 16);
        for (String id : seen) {
            sb.append(id).append(System.lineSeparator());
        }
        try {
            Files.writeString(file, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("{} 압축 완료 (최근 {}건 유지)", file, seen.size());
        } catch (IOException e) {
            log.warn("{} 압축 기록 실패", file, e);
        }
    }

    private void appendToFile(String id) {
        try {
            Files.writeString(file, id + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("{} 기록 실패: {}", file, id, e);
        }
    }
}
