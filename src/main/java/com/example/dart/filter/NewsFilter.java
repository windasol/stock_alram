package com.example.dart.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2단계 호재 필터 — 재현율(놓치지 않기) 우선.
 *
 * 이 봇의 목적은 호재 뉴스를 누구보다 빠르게, 최대한 많이 잡는 것이다.
 * 따라서 애매하면 보내고, 명백한 악재·중복 신호만 거른다.
 * (오탐 1건의 비용 << 호재 1건을 놓치는 비용)
 *
 * Stage 1 (matchTitle)      : 카테고리 룰 테이블 → 미매칭 시 "투자판단관련주요경영사항" 안전망 룰.
 * Stage 2 (bodyRejectReason): 수주공급계약 한정 — 명시적 조건부 계약, 매출액 대비 비율 미달만 제외.
 */
public class NewsFilter {

    /** 제목 매칭 결과 — 어떤 카테고리의 어떤 키워드에 걸렸는지. */
    public record TitleMatch(String category, String matchedKeyword) {}

    /**
     * 호재 룰: anyOf 중 1개 이상 포함 + allOf 전부 포함 + excludes 미포함이면 매칭.
     * 모든 키워드는 정규화(공백·가운뎃점 제거, 영문 대문자화)된 제목과 대조한다.
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
     * DART 표준 공시 양식명 기반 호재 룰 테이블. 첫 매칭 룰이 적용된다.
     * 재현율 우선: 호재일 "가능성"이 있는 표현은 최대한 넓게 잡는다.
     * 마지막의 안전망 룰이 코스닥 수시공시 통로인 "투자판단관련주요경영사항"을
     * 명백한 악재 키워드만 빼고 전부 통과시킨다.
     */
    private static final List<Rule> RULES = List.of(
            // "공급계약"은 체결·변경 구분 없이 — 해지는 전역 제외가 차단.
            // "우선협상대상자" 선정은 수주 확정 직전 신호로 가장 빠른 호재.
            new Rule(CATEGORY_CONTRACT,
                    List.of("단일판매", "공급계약", "수주", "우선협상대상자"),
                    List.of(), List.of()),
            // "배당결정"은 현금ㆍ현물ㆍ주식배당결정을 모두 커버.
            // "유무상증자결정"(희석 악재)은 "무상증자결정"을 부분문자열로 포함하므로 룰 제외로 차단.
            new Rule("주주환원",
                    List.of("자기주식취득", "주식소각결정", "이익소각", "배당결정",
                            "무상증자결정", "주식분할결정", "액면분할"),
                    List.of(), List.of("유무상증자")),
            new Rule("투자",
                    List.of("신규시설투자", "증설"),
                    List.of(), List.of()),
            // "체결" 요구 제거 — 경과 공시(마일스톤 수령 등)도 호재. 변경(축소·지연)만 제외.
            new Rule("기술계약",
                    List.of("기술이전", "기술수출", "라이선스계약", "라이센스계약"),
                    List.of(), List.of("변경")),
            new Rule("특허",
                    List.of("특허권취득", "특허취득", "특허등록"),
                    List.of(), List.of()),
            // "신청"은 이 룰에서만 제외 — 안전망 룰이 "주요경영사항"으로 받아 알림은 나간다.
            // "불승인"·"보류"는 악재이므로 차단 (안전망 룰에서도 제외됨).
            new Rule("바이오승인",
                    List.of("임상시험계획승인", "임상시험결과", "품목허가", "판매허가", "시판허가",
                            "희귀의약품지정", "혁신의료기기지정", "신속심사",
                            "FDA승인", "FDA허가", "EMA승인"),
                    List.of(), List.of("신청", "불승인", "보류")),
            // 신사업 진출·매출 목표 발표 등 — 펌핑성 호재 빈출 서식
            new Rule("장래계획",
                    List.of("장래사업"),  // "장래사업ㆍ경영계획(공정공시)" 정규화 후 매칭
                    List.of(), List.of()),
            // 중단됐던 생산·영업 재개는 명확한 호재
            new Rule("영업재개",
                    List.of("생산재개", "영업재개", "조업재개"),
                    List.of(), List.of()),
            // "소송등의판결ㆍ결정 (○○ 승소)" — 괄호 자유 텍스트에 승소가 명시된 경우만
            new Rule("소송승소",
                    List.of("승소"),
                    List.of(), List.of()),
            // 안전망: 코스닥 중대 호재(국책과제 선정, 대형 계약, 임상 이벤트 등)는
            // 대부분 이 공시명으로 나온다. 명백한 악재 키워드만 빼고 전부 알림.
            new Rule("주요경영사항",
                    List.of("투자판단관련주요경영사항"),
                    List.of(),
                    List.of("소송제기", "피소", "패소", "횡령", "배임", "압수수색",
                            "영업정지", "거래정지", "회생", "파산", "부도", "감자",
                            "관리종목", "상장폐지", "불승인", "기각", "보류",
                            "손해배상", "과징금", "변경", "유상증자", "실패"))
    );

    // ── Stage 2 : 본문 텍스트 필터 ───────────────────────────────────────

    /**
     * 수주공급계약 서식에는 "조건부 계약여부" 항목이 항상 존재하므로
     * 단순 "조건부" 포함 검사는 모든 계약 공시를 오제외한다.
     * 명시적으로 "해당"인 경우만 제외 ("미해당"은 "미"가 한글이라 매칭되지 않음).
     */
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "조건부\\s*계약\\s*여부[^가-힣0-9]{0,10}해당"
    );

    /**
     * 매출액 대비 계약금액 비율 최소 기준 (%).
     * 수주공급계약 공시에 비율이 명시된 경우에만 적용.
     * 재현율 우선으로 5% — 유가증권 의무공시 기준(5%) 수준의 계약까지 모두 알림.
     */
    private static final double MIN_SALES_RATIO = 5.0;

    /**
     * "매출액 대비(%) 12.5" / "최근매출액대비: 10.2%" 등에서 비율만 추출.
     * "대비"를 요구해 "최근 매출액(원) 50,000,000,000" 같은 금액 행 오매칭을 차단하고,
     * 값이 "-"(미기재)이거나 콤마 포함(1,031.5%)인 경우도 처리한다.
     */
    private static final Pattern RATIO_PATTERN = Pattern.compile(
            "매출액\\s*대비\\s*(?:\\(\\s*%\\s*\\))?\\s*:?\\s*(-|[0-9][0-9,]*(?:\\.[0-9]+)?)"
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
     * Stage 2: 원문 본문으로 규모·확정 여부 검증 — 수주공급계약에만 적용.
     * 다른 카테고리는 본문 보일러플레이트로 인한 오제외를 막기 위해 무조건 통과.
     *
     * 제외 조건:
     *  ① "조건부 계약여부: 해당" 명시 → 미확정 계약
     *  ② 계약금액/매출액 비율이 명시됐으나 5% 미만 → 규모 미미
     *
     * @return 제외 사유. 통과면 empty.
     */
    public Optional<String> bodyRejectReason(String bodyText, TitleMatch match) {
        if (bodyText == null || bodyText.isBlank()) return Optional.empty();
        if (!CATEGORY_CONTRACT.equals(match.category())) return Optional.empty();

        if (CONDITIONAL_PATTERN.matcher(bodyText).find()) {
            return Optional.of("조건부 계약(해당)");
        }

        OptionalDouble ratio = extractSalesRatio(bodyText);
        if (ratio.isPresent() && ratio.getAsDouble() < MIN_SALES_RATIO) {
            return Optional.of(String.format("매출액 대비 %.1f%% < %.0f%%", ratio.getAsDouble(), MIN_SALES_RATIO));
        }

        return Optional.empty();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * 공백·가운뎃점 변형 제거 + 영문 대문자화(fda → FDA)로 키워드 매칭을 안정화.
     * 대괄호 접두어([기재정정] 등)는 제외 신호이므로 보존.
     * DART·KIND 교차 중복 키(DisclosureKeys)도 같은 정규화를 쓴다 — 두 시스템의 표기 차이 흡수.
     */
    public static String normalize(String s) {
        return NORMALIZE_PATTERN.matcher(s).replaceAll("").toUpperCase(Locale.ROOT);
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
            String value = m.group(1);
            if ("-".equals(value)) return OptionalDouble.empty();
            try {
                return OptionalDouble.of(Double.parseDouble(value.replace(",", "")));
            } catch (NumberFormatException ignored) {
            }
        }
        return OptionalDouble.empty();
    }
}
