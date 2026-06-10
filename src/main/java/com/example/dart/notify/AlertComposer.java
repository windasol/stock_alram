package com.example.dart.notify;

import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 호재 공시 1건을 채널 공통 마크다운 알림 메시지로 조립한다.
 * 상세 내역(원문 요약) 조회에 실패하면 헤더만으로 메시지를 만든다 — 알림을 놓치지 않는 것이 우선.
 */
public class AlertComposer {

    private static final Logger log = LoggerFactory.getLogger(AlertComposer.class);

    private final DocumentService documentService;

    public AlertComposer(DocumentService documentService) {
        this.documentService = documentService;
    }

    public String compose(Disclosure d, NewsFilter.TitleMatch match) {
        String header = String.format(
                "**[호재 공시 · %s · %s]**\n**회사명**: %s (%s)\n**공시제목**: %s\n**접수일**: %s\n**제출인**: %s\n%s",
                d.marketName(), match.category(), d.corpName(), d.marketName(), d.reportNm(),
                formatDate(d.rceptDt()), d.flrNm(), dartUrl(d.rceptNo()));

        try {
            return header + "\n\n---\n" + documentService.buildDetail(d.rceptNo());
        } catch (Exception e) {
            log.warn("상세 내역 조회 실패 — 헤더만 전송: {} - {}", d.corpName(), d.reportNm(), e);
            return header;
        }
    }

    private static String dartUrl(String rceptNo) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo;
    }

    /** "20260610" → "2026-06-10" */
    private static String formatDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }
}
