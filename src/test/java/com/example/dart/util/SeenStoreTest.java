package com.example.dart.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeenStoreTest {

    @TempDir
    Path dir;

    @Test
    void 처음_본_ID만_true_중복은_false() {
        SeenStore store = new SeenStore(dir.resolve("seen.txt"));
        assertTrue(store.add("A"));
        assertFalse(store.add("A"));
        assertTrue(store.contains("A"));
        assertFalse(store.contains("B"));
    }

    @Test
    void 재시작하면_이전_상태를_복원한다() {
        Path file = dir.resolve("seen.txt");
        SeenStore first = new SeenStore(file);
        first.add("X");
        first.add("Y");

        SeenStore restarted = new SeenStore(file);   // 같은 파일로 새 인스턴스(재시작 모사)
        assertTrue(restarted.contains("X"));
        assertTrue(restarted.contains("Y"));
        assertFalse(restarted.add("X"));   // 이미 본 것
    }

    @Test
    void 상한을_초과하면_오래된_항목부터_잘라낸다() throws IOException {
        Path file = dir.resolve("seen.txt");
        int max = 10;
        SeenStore store = new SeenStore(file, max);
        // 상한(10) + 슬랙(2000)을 넘겨 압축을 유발한다.
        int total = max + 2_000 + 5;
        for (int i = 0; i < total; i++) {
            store.add("id-" + i);
        }
        // 가장 오래된 것들은 제거되고 최근 것만 남는다.
        assertFalse(store.contains("id-0"));
        assertTrue(store.contains("id-" + (total - 1)));

        // 파일도 압축돼 상한 수준으로만 유지된다(무한 증가 방지).
        List<String> lines = Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        assertTrue(lines.size() <= max + 2_000,
                "파일 라인 수가 상한+슬랙 이하여야 함: " + lines.size());
    }

    @Test
    void 상한을_넘긴_기존_파일은_로드시_정리된다() throws IOException {
        Path file = dir.resolve("seen.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("old-").append(i).append(System.lineSeparator());
        }
        Files.writeString(file, sb.toString());

        SeenStore store = new SeenStore(file, 100);   // 상한 100
        // 로드 직후 압축돼 파일이 상한 수준으로 줄어든다.
        List<String> lines = Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        assertEquals(100, lines.size());
        // 최근(뒤쪽) 항목은 남고 오래된(앞쪽) 항목은 제거된다.
        assertTrue(store.contains("old-499"));
        assertFalse(store.contains("old-0"));
    }
}
