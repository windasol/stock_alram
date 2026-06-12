package com.example.dart.notify;

import com.example.dart.filter.NewsFilter;
import com.example.dart.model.Disclosure;
import com.example.dart.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 호재 공시 1건을 채널 공통 마크다운 알림 메시지로 조립한다.
 * 상세 내역(원문 요약) 조회에 실패하면 헤더만으로 메시지를 만든다 — 알림을 놓치지 않는 것이 우선.
 */
public class AlertComposer {

    private static final Logger log = LoggerFactory.getLogger(AlertComposer.class);

    /** DART 목록 API는 접수 "일"만 주므로, 시각은 감지 시점(폴링 7초 주기 ≈ 게시 시각)으로 보여준다. */
    private static final DateTimeFormatter DETECT_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DocumentService documentService;

    public AlertComposer(DocumentService documentService) {
        this.documentService = documentService;
    }

    public String compose(Disclosure d, NewsFilter.TitleMatch match) {
        // 첫 줄에 이모지 + 카테고리 + 회사명 — 뉴스 알림과 같은 훑어보기 형식
        String header = String.format(
                "📋 **%s · %s** | %s — %s\n접수 %s · 감지 %s · 제출인 %s\n%s",
                match.category(), d.marketName(), d.corpName(), d.reportNm(),
                formatDate(d.rceptDt()), DETECT_TIME_FMT.format(ZonedDateTime.now(KST)),
                d.flrNm(), dartUrl(d.rceptNo()));

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
