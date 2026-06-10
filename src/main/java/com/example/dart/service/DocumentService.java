package com.example.dart.service;

import com.example.dart.dart.DartClient;
import com.example.dart.parse.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * 같은 공시 원문을 Stage 2 필터와 알림 상세에서 연달아 쓰므로,
     * 두 번 다운로드하지 않도록 최근 문서를 캐시 — 알림 지연 절반 감소.
     */
    private static final int CACHE_SIZE = 64;

    private final DartClient dartClient;
    private final DocumentParser documentParser;
    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public DocumentService(DartClient dartClient, DocumentParser documentParser) {
        this.dartClient = dartClient;
        this.documentParser = documentParser;
    }

    /** Stage 2 필터용: 원문 본문을 평문으로 반환. */
    public String toPlainText(String rceptNo) {
        return documentParser.toPlainText(fetchBytes(rceptNo));
    }

    /** 알림 메시지용: 핵심 정보 요약 + 미리보기 반환. 원문 링크는 헤더에 있으므로 포함하지 않는다. */
    public String buildDetail(String rceptNo) {
        log.info("상세 내역 조회 시작: {}", rceptNo);
        return documentParser.extractSummary(fetchBytes(rceptNo));
    }

    private byte[] fetchBytes(String rceptNo) {
        byte[] cached = cache.get(rceptNo);
        if (cached != null) return cached;
        byte[] bytes = dartClient.fetchDocument(rceptNo);
        cache.put(rceptNo, bytes);
        return bytes;
    }
}
