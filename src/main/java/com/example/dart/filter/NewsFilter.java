package com.example.dart.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2단계 호재 필터.
 *
 * Stage 1 (matchTitle)      : DART 표준 공시명 기준 — 정규화된 제목을 룰 테이블과 대조해 카테고리 판정.
 * Stage 2 (bodyRejectReason): 원문 본문 기준 — 미확정 공시 제외, 계약금액 비율 검증(수주공급계약 한정).
 */
public class NewsFilter {

    /** 제목 매칭 결과 — 어떤 카테고리의 어떤 키워드에 걸렸는지. */
    public record TitleMatch(String category, String matchedKeyword) {}

    /**
     * 호재 룰: anyOf 중 1개 이상 포함 + allOf 전부 포함 + excludes 미포함이면 매칭.
     * 모든 키워드는 정규화(공백·가운뎃점 제거)된 제목과 대조하므로 점 없이 작성한다.
     */
    private record Rule(String category, List<String> anyOf, List<String> allOf, List<String> excludes) {}

    public static final String CATEGORY_CONTRACT = "수주공급계약";

    // ── Stage 1 : 공시 제목 필터 ─────────────────────────────────────────

    /**
     * 호재처럼 보이지만 취소·보정·철회 공시.
     * 정규화된 제목에 하나라도 포함되면 즉시 제외.
     * "정정"이 "[기재정정]", "[첨부정정]" 등 모든 정정 변형을 커버한다.
     */
    private static final List<String> GLOBAL_EXCLUDES = List.of(
            "정정",   // 기존 공시 수정본 — 중복 알림 방지
            "해지",   // 계약 해지 = 악재
            "철회", "취소", "중단", "취하", "반려"
    );

    /**
     * DART 표준 공시 양식명 기반 호재 룰 테이블.
     * report_nm은 "단일판매ㆍ공급계약체결", "주요사항보고서(자기주식취득결정)" 같은 정형화된 문자열.
     * 첫 매칭 룰이 적용된다.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(CATEGORY_CONTRACT,
                    List.of("단일판매", "공급계약체결", "수주"),
                    List.of(), List.of()),
            // "배당결정"은 현금ㆍ현물ㆍ주식배당결정을 모두 커버.
            // "유무상증자결정"(희석 악재)은 "무상증자결정"을 부분문자열로 포함하므로 룰 제외로 차단.
            new Rule("주주환원",
                    List.of("자기주식취득", "주식소각결정", "배당결정", "무상증자결정", "주식분할결정", "액면분할"),
                    List.of(), List.of("유무상증자")),
            new Rule("투자",
                    List.of("신규시설투자"),
                    List.of(), List.of()),
            // "체결" 필수 — 단순 경과·변경 공시 차단
            new Rule("기술계약",
                    List.of("기술이전", "기술수출"),
                    List.of("체결"), List.of("변경")),
            new Rule("특허",
                    List.of("특허권취득"),
                    List.of(), List.of()),
            // "신청" 제외 — "임상시험계획승인신청"(승인 아님)은 호재가 아님
            new Rule("바이오승인",
                    List.of("임상시험계획승인", "품목허가"),
                    List.of(), List.of("신청"))
    );

    // ── Stage 2 : 본문 텍스트 필터 ───────────────────────────────────────

    /** 확정되지 않은 공시 식별자 — 포함 시 제외. */
    private static final List<String> UNCERTAIN_BODY = List.of(
            "조건부"
    );

    /**
     * 매출액 대비 계약금액 비율 최소 기준 (%).
     * 수주공급계약 공시에 비율이 명시된 경우에만 적용.
     * 미만이면 주가 영향이 미미하다고 판단해 제외.
     */
    private static final double MIN_SALES_RATIO = 10.0;

    // "매출액 대비(%) 15.5" / "최근매출액대비 10.2%" 등 다양한 표현 포괄
    private static final Pattern RATIO_PATTERN = Pattern.compile(
            "매출액[^0-9]{0,30}([0-9]+(?:\\.[0-9]+)?)"
    );

    /** 공백 + 가운뎃점 변형(ㆍ · ・ ･ ∙ ⋅ •) 일괄 제거용. */
    private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[\\sㆍ·・･∙⋅•]+");

    private final List<Rule> rules;
    private final List<String> excludes;

    public NewsFilter() {
        this(List.of(), List.of());
    }

    /**
     * @param extraKeywords 환경변수(FILTER_EXTRA_KEYWORDS)로 추가하는 호재 키워드 — "사용자추가" 카테고리로 매칭
     * @param extraExcludes 환경변수(FILTER_EXCLUDE_KEYWORDS)로 추가하는 전역 제외 키워드
     */
    public NewsFilter(List<String> extraKeywords, List<String> extraExcludes) {
        List<Rule> rules = new ArrayList<>(RULES);
        if (!extraKeywords.isEmpty()) {
            rules.add(new Rule("사용자추가", normalizeAll(extraKeywords), List.of(), List.of()));
        }
        this.rules = List.copyOf(rules);

        List<String> excludes = new ArrayList<>(GLOBAL_EXCLUDES);
        excludes.addAll(normalizeAll(extraExcludes));
        this.excludes = List.copyOf(excludes);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Stage 1: 공시 제목만으로 호재 후보 여부 판단.
     * 본문 조회(네트워크) 없이 빠르게 필터링.
     *
     * @return 매칭된 카테고리·키워드. 비호재면 empty.
     */
    public Optional<TitleMatch> matchTitle(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) return Optional.empty();

        String title = normalize(reportNm);

        for (String ex : excludes) {
            if (title.contains(ex)) return Optional.empty();
        }

        for (Rule rule : rules) {
            Optional<String> matched = matchRule(rule, title);
            if (matched.isPresent()) {
                return Optional.of(new TitleMatch(rule.category(), matched.get()));
            }
        }

        return Optional.empty();
    }

    /**
     * Stage 2: 원문 본문으로 확정 여부·규모 검증.
     * Stage 1 통과 후 호출. 본문이 없으면 통과 처리.
     *
     * 제외 조건:
     *  ① 본문에 "조건부" 포함 → 미확정
     *  ② 수주공급계약: 계약금액/매출액 비율이 명시됐으나 10% 미만 → 규모 미미
     *
     * @return 제외 사유. 통과면 empty.
     */
    public Optional<String> bodyRejectReason(String bodyText, TitleMatch match) {
        if (bodyText == null || bodyText.isBlank()) return Optional.empty();

        for (String uncertain : UNCERTAIN_BODY) {
            if (bodyText.contains(uncertain)) {
                return Optional.of("미확정(" + uncertain + ")");
            }
        }

        // 매출액 비율 검증은 수주공급계약 본문에만 의미가 있음 —
        // 다른 공시의 "매출액 ..." 보일러플레이트로 인한 오제외 방지
        if (CATEGORY_CONTRACT.equals(match.category())) {
            OptionalDouble ratio = extractSalesRatio(bodyText);
            if (ratio.isPresent() && ratio.getAsDouble() < MIN_SALES_RATIO) {
                return Optional.of(String.format("매출액 대비 %.1f%% < %.0f%%", ratio.getAsDouble(), MIN_SALES_RATIO));
            }
        }

        return Optional.empty();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** 공백·가운뎃점 변형을 제거해 키워드 매칭을 안정화. 대괄호 접두어([기재정정] 등)는 제외 신호이므로 보존. */
    static String normalize(String s) {
        return NORMALIZE_PATTERN.matcher(s).replaceAll("");
    }

    private static List<String> normalizeAll(List<String> keywords) {
        return keywords.stream().map(NewsFilter::normalize).filter(s -> !s.isEmpty()).toList();
    }

    /** @return 매칭된 anyOf 키워드. 미매칭이면 empty. */
    private static Optional<String> matchRule(Rule rule, String title) {
        for (String ex : rule.excludes()) {
            if (title.contains(ex)) return Optional.empty();
        }
        for (String required : rule.allOf()) {
            if (!title.contains(required)) return Optional.empty();
        }
        for (String kw : rule.anyOf()) {
            if (title.contains(kw)) return Optional.of(kw);
        }
        return Optional.empty();
    }

    private static OptionalDouble extractSalesRatio(String bodyText) {
        Matcher m = RATIO_PATTERN.matcher(bodyText);
        if (m.find()) {
            try {
                return OptionalDouble.of(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return OptionalDouble.empty();
    }
}
