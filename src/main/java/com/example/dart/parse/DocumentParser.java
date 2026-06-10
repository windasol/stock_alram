package com.example.dart.parse;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);
    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private static final String[] LABELS = {
            "계약금액", "계약상대방", "최근매출액", "매출액대비",
            "계약기간", "대상회사", "취득주식수", "취득금액",
            "투자금액", "시설규모"
    };

    public String toPlainText(byte[] zipBytes) {
        byte[] xmlBytes = unzip(zipBytes);
        String raw = decodeWithFallback(xmlBytes);
        return Jsoup.parse(raw).text().replaceAll("\\s{2,}", " ").trim();
    }

    public String extractSummary(byte[] zipBytes) {
        String plainText = toPlainText(zipBytes);

        Map<String, String> extracted = new LinkedHashMap<>();
        for (String label : LABELS) {
            Pattern pattern = Pattern.compile(label + "\\s*[:\\-]?\\s*(.{1,100})");
            Matcher matcher = pattern.matcher(plainText);
            if (matcher.find()) {
                extracted.put(label, matcher.group(1).trim());
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!extracted.isEmpty()) {
            sb.append("**[핵심 정보]**\n");
            extracted.forEach((k, v) -> sb.append("• ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }

        sb.append("**[본문 미리보기]**\n");
        int previewLen = Math.min(plainText.length(), 800);
        sb.append(plainText, 0, previewLen);
        if (plainText.length() > previewLen) {
            sb.append("...");
        }

        return sb.toString();
    }

    private byte[] unzip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".html")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    zis.transferTo(baos);
                    return baos.toByteArray();
                }
            }
            throw new RuntimeException("ZIP 내 xml/html 파일을 찾을 수 없음");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ZIP 해제 실패", e);
        }
    }

    private String decodeWithFallback(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("�")) return utf8;

        log.debug("UTF-8 디코딩 실패, EUC-KR로 재시도");
        return new String(bytes, EUC_KR);
    }
}
