package com.example.dart.service;

import com.example.dart.dart.DartClient;
import com.example.dart.parse.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DartClient dartClient;
    private final DocumentParser documentParser;

    public DocumentService(DartClient dartClient, DocumentParser documentParser) {
        this.dartClient = dartClient;
        this.documentParser = documentParser;
    }

    public String buildDetail(String rceptNo) {
        log.info("상세 내역 조회 시작: {}", rceptNo);
        byte[] zipBytes = dartClient.fetchDocument(rceptNo);
        String summary = documentParser.extractSummary(zipBytes);

        String dartUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;
        return summary + "\n\n" + dartUrl;
    }
}
