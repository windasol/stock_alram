package com.example.dart.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SeenStore {

    private static final Logger log = LoggerFactory.getLogger(SeenStore.class);
    private static final Path SEEN_FILE = Path.of("seen.txt");

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public SeenStore() {
        loadFromFile();
    }

    public boolean contains(String rceptNo) {
        return seen.contains(rceptNo);
    }

    public void add(String rceptNo) {
        if (seen.add(rceptNo)) {
            appendToFile(rceptNo);
        }
    }

    private void loadFromFile() {
        if (!Files.exists(SEEN_FILE)) return;
        try {
            Files.readAllLines(SEEN_FILE).stream()
                    .filter(line -> !line.isBlank())
                    .forEach(seen::add);
            log.info("이전 공시 {}건 로드 완료", seen.size());
        } catch (IOException e) {
            log.warn("seen.txt 로드 실패, 빈 상태로 시작", e);
        }
    }

    private void appendToFile(String rceptNo) {
        try {
            Files.writeString(SEEN_FILE, rceptNo + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("seen.txt 기록 실패: {}", rceptNo, e);
        }
    }
}
