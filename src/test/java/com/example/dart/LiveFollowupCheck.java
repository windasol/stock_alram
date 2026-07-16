package com.example.dart;

import com.example.dart.disclosure.infra.DartClient;
import com.example.dart.disclosure.domain.NewsFilter;
import com.example.dart.disclosure.infra.KindClient;
import com.example.dart.disclosure.infra.KindDocumentClient;
import com.example.dart.disclosure.domain.Disclosure;
import com.example.dart.disclosure.application.AlertComposer;
import com.example.dart.disclosure.infra.DocumentParser;
import com.example.dart.common.infra.StockQuoteClient;
import com.example.dart.disclosure.application.DocumentService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수동 라이브 점검 — 실제 DART 데이터로 규모 분석 후속 메시지가 끝까지 만들어지는지 출력만 한다.
 * 평소엔 실행하지 않고, `gradlew test --tests *LiveFollowupCheck` 로만 돌린다.
 */
@Disabled("수동 라이브 점검 — 네트워크·실키 필요. 실행: @Disabled 제거 후 gradlew test --tests *LiveFollowupCheck")
class LiveFollowupCheck {

    @Test
    void printFollowupForRealDisclosure() throws Exception {
        String env = Files.readString(Path.of(".env"));
        Matcher m = Pattern.compile("DART_API_KEY=([^\\r\\n]+)").matcher(env);
        if (!m.find()) { System.out.println("API 키 없음"); return; }
        String key = m.group(1).trim();

        DartClient dartClient = new DartClient(key);
        DocumentParser documentParser = new DocumentParser();
        DocumentService documentService = new DocumentService(dartClient, documentParser);
        NewsFilter newsFilter = new NewsFilter();
        AlertComposer composer = new AlertComposer(documentService, newsFilter, new StockQuoteClient(), dartClient,
                new KindClient(), new KindDocumentClient(), documentParser);

        Disclosure[] tests = {
                // 대한전선 (코스피 Y), 에너토크 (코스닥 K) — 둘 다 원문 ZIP 정상
                new Disclosure("대한전선", "00113207", "001440", "Y",
                        "단일판매ㆍ공급계약체결", "20260617800062", "20260617", "대한전선"),
                new Disclosure("에너토크", "00169215", "019990", "K",
                        "단일판매ㆍ공급계약체결", "20260617900178", "20260617", "에너토크"),
        };

        StringBuilder out = new StringBuilder();
        for (Disclosure d : tests) {
            Optional<NewsFilter.TitleMatch> match = newsFilter.matchTitle(d.reportNm());
            out.append("\n========== ").append(d.corpName()).append(" ==========\n");
            if (match.isEmpty()) { out.append("matchTitle 매칭안됨\n"); continue; }
            out.append("--- 빠른 경로(KIND 본문) ---\n");
            try {
                out.append(composer.composeFollowupFast(d)).append("\n");
            } catch (Exception e) {
                out.append("빠른 경로 실패(폴백 대상): ").append(e).append("\n");
            }
            out.append("--- 폴백 경로(DART 원문) ---\n");
            out.append(composer.composeFollowup(d)).append("\n");
        }
        Files.writeString(Path.of("build", "followup-out.txt"), out.toString());
        System.out.println(out);
    }
}
