package com.example.dart.parse;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** 본문으로 우선 채택할 텍스트 문서 확장자 (대소문자 무시). */
    private static final String[] TEXT_EXTS = {".xml", ".html", ".htm", ".txt"};

    /** 본문 후보에서 제외할 이미지·바이너리 확장자 (대소문자 무시). */
    private static final String[] BINARY_EXTS =
            {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".pdf"};

    /** ZIP 로컬 파일 헤더 시그니처 (PK). */
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    public String toPlainText(byte[] zipBytes) {
        return htmlToPlainText(unzip(zipBytes));
    }

    /**
     * ZIP을 거치지 않는 평문 추출 — KIND 뷰어 본문(.htm, EUC-KR)처럼 압축되지 않은 HTML 바이트용.
     * DART 경로의 {@link #toPlainText(byte[])}와 같은 정리(태그 제거·공백 정규화)를 공유한다.
     */
    public String htmlToPlainText(byte[] htmlBytes) {
        String raw = decodeWithFallback(htmlBytes);
        return Jsoup.parse(raw).text().replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * 수주공급계약 공시에서 알림에 쓸 핵심값만 "깔끔하게" 추출한다.
     * 기존 라벨+100자 캡처는 옆 필드까지 끌려와 지저분하므로, 라벨에 딱 붙는 값만 정밀 매칭한다.
     * 라벨 표기는 공백·콜론 유무가 제각각이라("최근매출액(원)" vs "최근 매출액(원)") 정규식에서 흡수한다.
     */
    public ContractInfo extractContract(byte[] zipBytes) {
        return extractContractFromText(toPlainText(zipBytes));
    }

    /**
     * 평문에서 수주공급계약 핵심값을 추출한다. DART 원문(ZIP)·KIND 뷰어 본문(.htm) 모두
     * 라벨 표기가 동일하므로 같은 정규식을 공유한다.
     */
    public ContractInfo extractContractFromText(String t) {
        return new ContractInfo(
                wonAfter(t, "계약금액\\s*(?:총액)?\\s*\\(\\s*원\\s*\\)"),  // 계약금액(총액)(원) NN
                ratioPct(t),                                              // 매출액 대비(%) NN.NN
                wonAfter(t, "최근\\s*매출액\\s*\\(\\s*원\\s*\\)"),          // 최근 매출액(원) NN
                counterparty(t),                                          // 계약상대방 XXX
                period(t));                                               // 계약기간 시작~종료
    }

    /**
     * 자기주식취득 결정 본문에서 취득(예정)금액(원)을 뽑는다 — 직접취득결정 알림에 금액 한 줄을 덧붙이는 용도.
     * 서식 표기는 "취득예정금액(원)" 우선, 일부는 "취득금액(원)". 라벨과 숫자 사이에 "보통주식" 같은
     * 표 칸 텍스트가 끼어들 수 있으므로 숫자 직전까지 비숫자 약간을 허용한다. 6자리 이상(콤마 포함) 금액만.
     */
    public OptionalLong acquisitionAmountWon(String t) {
        for (String label : new String[]{"취득\\s*예정\\s*금액", "취득\\s*금액"}) {
            Matcher m = Pattern.compile(label + "\\s*\\(\\s*원\\s*\\)[^0-9]{0,12}([0-9][0-9,]{5,})").matcher(t);
            if (m.find()) {
                try {
                    return OptionalLong.of(Long.parseLong(m.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return OptionalLong.empty();
    }

    /** 라벨 정규식 바로 뒤에 오는 정수 금액(콤마 포함)을 원(long)으로. 없으면 empty. */
    private static java.util.OptionalLong wonAfter(String text, String labelRegex) {
        Matcher m = Pattern.compile(labelRegex + "\\s*([0-9][0-9,]+)").matcher(text);
        if (m.find()) {
            try {
                return java.util.OptionalLong.of(Long.parseLong(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
            }
        }
        return java.util.OptionalLong.empty();
    }

    /** "매출액 대비(%) 3.66" → 3.66. 미기재("-")·없음이면 null. */
    private static Double ratioPct(String text) {
        Matcher m = Pattern.compile("매출액\\s*대비\\s*\\(\\s*%\\s*\\)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * "계약상대방 WAA EUROPE - 최근 매출액…" → "WAA EUROPE". 못 찾으면 null.
     * 라벨은 공시마다 "계약상대방"(에너토크)·"계약상대"(대한전선·한화오션) 둘 다 쓰므로 '방'을 선택적으로 둔다.
     * 콜론 표기도 흡수한다. 값이 "-"(비공개)면 매칭되지 않아 null — 미기재로 정상 처리된다.
     */
    private static String counterparty(String text) {
        Matcher m = Pattern.compile(
                "계약상대(?:방)?\\s*[:：]?\\s+([^-]{1,40}?)\\s*(?:-|최근\\s*매출액|주요\\s*사업|회사와)").matcher(text);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /** "계약기간 시작일 2025-08-19 종료일 2028-06-28" → "2025-08-19 ~ 2028-06-28". 정정 공시는 마지막(정정후) 값. */
    private static String period(String text) {
        Matcher m = Pattern.compile("시작일\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2})\\s*종료일\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2})").matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1) + " ~ " + m.group(2);
        }
        return last;
    }

    /**
     * 매출 대비 표시 문자열. 공시 명시 비율(statedPct = 거래소 표준 지표: 계약총액 ÷ 최근 연매출)을 그대로 쓰고,
     * 없으면 계약금액 ÷ 매출액으로 계산한다.
     *
     * 연환산(÷계약연수)은 하지 않는다 — 공시·시장이 쓰는 공식 수치를 변형하면 공시에 적힌 값과 어긋나
     * "틀린 값"처럼 보이고, 계약기간 시작이 과거인 정정·장기계약에선 경과분까지 나뉘어 더 왜곡된다.
     * 다년 여부는 계약기간을 별도(📈 줄)로 표시해 확인한다.
     *
     * @return 비율을 못 구하면 null
     */
    public static String salesRatioLabel(long contractWon, OptionalLong revenue, Double statedPct) {
        java.util.OptionalDouble pct = salesRatioValue(contractWon, revenue, statedPct);
        return pct.isPresent() ? String.format("매출 대비 %.1f%%", pct.getAsDouble()) : null;
    }

    /**
     * 매출 대비 비율의 숫자값(%). 공시 명시 비율(statedPct)을 우선하고, 없으면 계약금액 ÷ 매출액으로 계산한다.
     * 자동매매 트리거(≥N%) 판정과 {@link #salesRatioLabel} 표시가 같은 값을 쓰도록 로직을 단일화한다.
     *
     * @return 비율을 못 구하면 비어있음(OptionalDouble.empty)
     */
    public static java.util.OptionalDouble salesRatioValue(long contractWon, OptionalLong revenue, Double statedPct) {
        if (statedPct != null) return java.util.OptionalDouble.of(statedPct);
        if (revenue.isPresent() && revenue.getAsLong() > 0) {
            return java.util.OptionalDouble.of(contractWon * 100.0 / revenue.getAsLong());
        }
        return java.util.OptionalDouble.empty();
    }

    /** 수주공급계약 알림용 핵심값. 없는 항목은 비어있음/ null. */
    public record ContractInfo(
            java.util.OptionalLong contractWon,
            Double salesRatioPct,
            java.util.OptionalLong recentRevenueWon,
            String counterparty,
            String period) {}

    /** 본문에서 핵심 라벨(계약금액·최근매출액·매출액대비 등) → 값 Map을 추출. 규모 계산에 쓰인다. */
    public Map<String, String> extractFields(byte[] zipBytes) {
        return extractFieldsFromText(toPlainText(zipBytes));
    }

    private Map<String, String> extractFieldsFromText(String plainText) {
        Map<String, String> extracted = new LinkedHashMap<>();
        for (String label : LABELS) {
            Pattern pattern = Pattern.compile(label + "\\s*[:\\-]?\\s*(.{1,100})");
            Matcher matcher = pattern.matcher(plainText);
            if (matcher.find()) {
                extracted.put(label, matcher.group(1).trim());
            }
        }
        return extracted;
    }

    public String extractSummary(byte[] zipBytes) {
        String plainText = toPlainText(zipBytes);
        Map<String, String> extracted = extractFieldsFromText(plainText);

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
        if (!looksLikeZip(zipBytes)) {
            String preview = new String(zipBytes, 0, Math.min(zipBytes.length, 200), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            log.warn("DART가 ZIP이 아닌 응답을 반환 ({}바이트, 앞부분: {})", zipBytes.length, preview);
        }

        // 폴백(최대 엔트리 선택)을 위해 모든 엔트리를 한 번 순회하며 (이름, 내용)을 수집한다.
        List<Entry> textEntries = new ArrayList<>();
        List<Entry> otherEntries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) continue;
                String name = zipEntry.getName();
                if (hasExtension(name, BINARY_EXTS)) continue;   // 이미지·PDF 등은 본문 후보에서 제외

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                zis.transferTo(baos);
                Entry entry = new Entry(name, baos.toByteArray());
                if (hasExtension(name, TEXT_EXTS)) {
                    textEntries.add(entry);
                } else {
                    otherEntries.add(entry);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("ZIP 해제 실패", e);
        }

        // 1순위: 텍스트 확장자 엔트리 중 가장 먼저 만난 것.
        if (!textEntries.isEmpty()) {
            return textEntries.get(0).content;
        }

        // 2순위 폴백: 비바이너리 엔트리 중 가장 큰 것 (DART 본문은 대개 최대 크기).
        Entry largest = otherEntries.stream()
                .max((a, b) -> Integer.compare(a.content.length, b.content.length))
                .orElse(null);
        if (largest != null) {
            log.info("ZIP에 텍스트 확장자 엔트리 없음 — 최대 엔트리로 폴백: {} ({}바이트)",
                    largest.name, largest.content.length);
            return largest.content;
        }

        // 본문 후보가 전혀 없음 (빈 ZIP / 디렉터리·이미지뿐). 진단을 위해 엔트리 목록을 남긴다.
        throw new RuntimeException("ZIP 내 본문 파일을 찾을 수 없음 — 엔트리: " + describeAll(zipBytes));
    }

    /** 응답 바이트가 ZIP 로컬 파일 헤더 시그니처로 시작하는지 확인. */
    private static boolean looksLikeZip(byte[] bytes) {
        if (bytes.length < ZIP_SIGNATURE.length) return false;
        for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
            if (bytes[i] != ZIP_SIGNATURE[i]) return false;
        }
        return true;
    }

    private static boolean hasExtension(String name, String[] exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 진단용: ZIP 내 모든 엔트리의 이름·크기를 다시 한 번 훑어 "name(bytes)" 목록으로 만든다. */
    private static String describeAll(byte[] zipBytes) {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                long size = e.getSize();
                entries.add(e.getName() + (size >= 0 ? "(" + size + "B)" : ""));
            }
        } catch (Exception ignored) {
            // 진단 경로이므로 실패해도 무시
        }
        return entries.isEmpty() ? "(없음)" : entries.stream().collect(Collectors.joining(", "));
    }

    private record Entry(String name, byte[] content) {}

    private String decodeWithFallback(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("�")) return utf8;

        log.debug("UTF-8 디코딩 실패, EUC-KR로 재시도");
        return new String(bytes, EUC_KR);
    }
}
