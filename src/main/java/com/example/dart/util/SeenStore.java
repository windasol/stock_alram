package com.example.dart.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 이미 처리한 항목 ID를 파일로 영속 저장 — 재시작해도 중복 알림을 막는다. */
public class SeenStore {

    private static final Logger log = LoggerFactory.getLogger(SeenStore.class);

    private final Path file;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public SeenStore() {
        this(Path.of("seen.txt"));
    }

    public SeenStore(Path file) {
        this.file = file;
        loadFromFile();
    }

    public boolean contains(String id) {
        return seen.contains(id);
    }

    /** @return 처음 본 ID였으면 true — 두 폴러가 같은 키를 두고 경쟁해도 한쪽만 true를 받는다. */
    public boolean add(String id) {
        if (seen.add(id)) {
            appendToFile(id);
            return true;
        }
        return false;
    }

    private void loadFromFile() {
        if (!Files.exists(file)) return;
        try {
            Files.readAllLines(file).stream()
                    .filter(line -> !line.isBlank())
                    .forEach(seen::add);
            log.info("이전 처리 항목 {}건 로드 완료 ({})", seen.size(), file);
        } catch (IOException e) {
            log.warn("{} 로드 실패, 빈 상태로 시작", file, e);
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
