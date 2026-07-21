package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.common.infra.MarketCalendar;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.KisMoney;
import com.example.dart.kis.domain.MarketInvestorFlow;
import com.example.dart.kis.domain.Session;
import com.example.dart.kis.infra.DomesticMarketClient;
import com.example.dart.kis.infra.KisClient;
import com.example.dart.kis.infra.SectorCacheStore;
import com.example.dart.kis.infra.UsFuturesClient;
import com.example.dart.llm.LlmClient;
import com.example.dart.news.NewsArticle;
import com.example.dart.news.NewsHeadlineBuffer;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 시황 매크로 분석 — 국내 수급·미선물 '실측'을 근거로 깔고, Gemini 검색 그라운딩으로
 * "지금 왜?"(미국 선물 등락 이유·경제 일정·글로벌 시황)를 끌어와 국내 흐름과 엮어 뉴스 채널로 발송한다.
 * 모델이 컷오프 밖 사실을 지어내지 않도록 검색(그라운딩)으로만 외부 사실을 얻는다(하이브리드). 장중에만 동작.
 */
public class MacroReportService {

    private static final Logger log = LoggerFactory.getLogger(MacroReportService.class);
    private static final ZoneId KST = KstTime.ZONE;
    private static final DateTimeFormatter SUMMARY_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** 시황 분석 '기준 날짜' 앵커용 — 예: 2026년 07월 10일(금). 요일까지 박아 LLM이 '오늘/이번 주'를 정확히 잡게 한다. */
    private static final DateTimeFormatter MACRO_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일(E)", java.util.Locale.KOREAN);
    /** 시황 분석 facts 블록에 넣을 외국인·기관 수급 상위 종목 수(토큰 절약). */
    private static final int FACTS_TOP = 10;
    /** 시황 리포트에 넣을 뉴스 헤드라인 최대 건수 — 정규화 dedup 후 상한(토큰 절약). */
    private static final int NEWS_HEADLINES_MAX = 60;

    /** 장 흐름 분석 리포트용 LLM(Gemini/Ollama). */
    private final LlmClient llm;
    /** 시황 분석에 실시간 검색 그라운딩을 켤지. true면 chatGrounded(과금·실시간), false면 평문 chat. */
    private final boolean grounding;
    /** 분석 발송 채널 — 뉴스 채널(표·급등·섹터의 KIS 채널과 분리). */
    private final Notifier reportNotifier;
    private final KisClient client;
    private final SectorCacheStore sectors;
    /** 지난 1시간 뉴스 헤드라인 버퍼 — 뉴스 폴러가 쌓고 시황 리포트가 재료로 읽는다. null이면 뉴스 섹션 생략. */
    private final NewsHeadlineBuffer headlineBuffer;
    /** 리포트 '대외 여건'용 미국 선물 조회기. 실패해도 국내 리포트는 계속된다. */
    private final UsFuturesClient usFutures;
    /** 리포트 '국내 지수(코스피·코스닥)·원달러 환율'용 조회기. 실패해도 국내 리포트는 계속된다. */
    private final DomesticMarketClient domesticMarket;
    private final TurnoverRankingService turnoverRanking;
    private final InvestorFlowService investorFlow;
    private final MarketCalendar calendar;
    /** 리포트 주기(분) — 뉴스 헤드라인을 읽는 창의 길이로도 쓴다. */
    private final int intervalMin;

    public MacroReportService(LlmClient llm, boolean grounding, Notifier reportNotifier, KisClient client,
                              SectorCacheStore sectors, NewsHeadlineBuffer headlineBuffer,
                              UsFuturesClient usFutures, DomesticMarketClient domesticMarket,
                              TurnoverRankingService turnoverRanking, InvestorFlowService investorFlow,
                              MarketCalendar calendar, int intervalMin) {
        this.llm = llm;
        this.grounding = grounding;
        this.reportNotifier = reportNotifier;
        this.client = client;
        this.sectors = sectors;
        this.headlineBuffer = headlineBuffer;
        this.usFutures = usFutures;
        this.domesticMarket = domesticMarket;
        this.turnoverRanking = turnoverRanking;
        this.investorFlow = investorFlow;
        this.calendar = calendar;
        this.intervalMin = intervalMin;
    }

    /** 시작 시 LLM 연결을 1회 핑(ping)해 키·모델·네트워크 정상 여부를 로그로 남긴다. 분석 발송과 무관(장외에도 동작). */
    public void llmSelfTest() {
        try {
            String r = llm.chat("너는 연결 확인용이다. 군더더기 없이 'OK'만 답해.", "OK?");
            if (r != null && !r.isBlank()) {
                log.info("✅ LLM 연결 확인 OK ({}): \"{}\"", llm.model(), r.trim());
            } else {
                log.warn("⚠️ LLM 연결 확인 실패 ({}) — 응답 없음/차단. GEMINI_API_KEY·모델명·네트워크 확인", llm.model());
            }
        } catch (Exception e) {
            log.warn("⚠️ LLM 연결 확인 중 오류 ({}): {}", llm.model(), e.toString());
        }
    }

    /** 시황 매크로 분석 1건 생성·발송 — 장중(거래일)에만. */
    public void generate() {
        if (llm == null) return;
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!calendar.isTradingDay(now.toLocalDate())) return;
        Session sess = Session.at(now.toLocalTime());
        if (sess == null) return;   // 장중에만
        LocalTime time = now.toLocalTime();

        // 국내 실측 facts(지지 근거) — 외국인은 외국계 실시간(J), 기관·양매수는 거래소 가집계.
        List<InvestorFlowItem> fb = client.foreignMemberEstimate("J", true).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> fs = client.foreignMemberEstimate("J", false).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> ib = client.investorFlowRank(Investor.INSTITUTION, true).stream().limit(FACTS_TOP).toList();
        List<InvestorFlowItem> is = client.investorFlowRank(Investor.INSTITUTION, false).stream().limit(FACTS_TOP).toList();
        List<InvestorPairItem> db = InvestorFlowService.dualBuyTop(client.investorFlowDual(true), FACTS_TOP);

        List<String> codes = new ArrayList<>();
        for (InvestorFlowItem it : fb) codes.add(it.code());
        for (InvestorFlowItem it : fs) codes.add(it.code());
        for (InvestorFlowItem it : ib) codes.add(it.code());
        for (InvestorFlowItem it : is) codes.add(it.code());
        for (InvestorPairItem it : db) codes.add(it.code());
        Map<String, String> sectorByCode = sectors.resolve(codes);

        String turnover = turnoverRanking.build(sess, sess.label, time);  // 거래대금 섹터 랭킹(null 가능)
        String indexLine = domesticMarket.indexSummaryLine();             // 국내 지수 코스피·코스닥(null 가능)
        String fxLine = domesticMarket.fxSummaryLine();                   // 원달러 환율(null 가능)
        String usFuturesLine = usFutures.summaryLine();                   // 대외 여건(null 가능)
        // 시장 전체 수급 — 틱이 저장한 최신 스냅샷을 쓰되, 없으면(헤드라인 비활성 등) 즉석 조회.
        String marketFlowLine = InvestorFlowComposer.marketFlowLine(investorFlow.latestMarketFlows());
        // 기준 날짜·시각을 맨 앞에 박는다 — 이게 없으면 LLM/그라운딩이 '오늘/이번 주'를 몰라 지난(엉뚱한 날짜) 일정을 끌어온다.
        // 모델이 옳은 오늘 날짜를 받고도 학습기억으로 일정 날짜를 지어내는 문제가 있어, 검색 확인·추측금지를 강하게 못박는다.
        String dateAnchor = String.format(
                "[오늘] %s %s KST 기준이다. 아래 모든 '오늘/이번 주/다음 주'는 반드시 이 날짜로 계산하라.\n"
                + "[날짜 규칙] 네 학습기억의 날짜는 틀릴 수 있으니 신뢰하지 마라. 일정의 날짜·발표 여부는 오직 google_search 검색으로 "
                + "확인한 것만 사용하고, 검색으로 정확한 날짜가 확인되지 않는 일정은 아예 넣지 마라(추측·어림 금지). "
                + "특히 실적은 '이미 발표됐는지'를 검색으로 확인해, 오늘보다 과거에 발표된 건 미래 일정으로 넣지 마라.",
                MACRO_DATE_FMT.format(now), SUMMARY_TIME_FMT.format(time));
        String facts = dateAnchor + "\n\n"
                + buildFlowFacts(indexLine, marketFlowLine, fxLine, usFuturesLine, fb, fs, ib, is, db, turnover, sectorByCode);

        // 지난 리포트 주기(시간당) 동안 수집된 뉴스 헤드라인을 재료로 덧붙인다 — 개별 속보 발송을 대체하는 핵심.
        if (headlineBuffer != null) {
            List<NewsArticle> headlines = headlineBuffer.recent(Duration.ofMinutes(intervalMin), NEWS_HEADLINES_MAX);
            String newsBlock = buildNewsHeadlines(headlines, NEWS_HEADLINES_MAX);
            if (newsBlock != null) facts = facts + "\n\n" + newsBlock;
        }

        // 그라운딩 ON이면 실시간 검색으로 "왜?"를 끌어온다. 단 무료 티어/권한 문제로 그라운딩이 실패할 수 있어
        // 실패하면 평문(chat)으로 폴백한다 — 분석이 통째로 빠지지 않게(평문은 국내 실측만, "왜?"는 약함).
        String narrative = null;
        boolean grounded = false;
        if (grounding) {
            narrative = llm.chatGrounded(MACRO_SYSTEM_PROMPT, MACRO_USER_PROMPT + "\n\n" + facts);
            if (narrative != null) grounded = true;
            else log.warn("그라운딩 실패 — 평문 분석으로 폴백(무료 티어/권한·한도 확인)");
        }
        if (narrative == null) {
            narrative = llm.chat(MACRO_SYSTEM_PROMPT, MACRO_USER_PROMPT + "\n\n" + facts);
        }
        if (narrative == null) return;  // LLM 실패 — 이미 로깅됨, 분석만 거른다
        String msg = composeMacroMessage(narrative, time, grounded);
        try {
            reportNotifier.send(msg);   // 뉴스 채널로
            log.info("🌐 시황 매크로 분석 발송 (그라운딩 {})", grounded ? "ON" : "OFF(평문 폴백)");
        } catch (Exception e) {
            log.warn("시황 매크로 분석 전송 실패", e);
        }
    }

    /**
     * LLM 응답을 '현재 시황·시장상황'과 '캘린더' 두 타이틀 섹션으로 조립한다(순수 함수 — 전송 없음, 테스트용).
     * 프롬프트가 두 블록을 {@link #CALENDAR_MARKER}로 가르므로 그 토큰으로 쪼갠다. 마커가 없으면(형식 이탈)
     * 전체를 시황 섹션 하나로만 내보내 최소한 분석은 빠지지 않게 한다.
     *
     * 캘린더는 grounded=true(실시간 검색 성공)일 때만 내보낸다 — 평문 폴백은 검색이 없어 날짜를 지어내므로(환각)
     * 통째로 생략하고 사유만 남긴다. 틀린 미래 일정이 나가는 것보다 캘린더가 없는 편이 안전하다.
     */
    static String composeMacroMessage(String narrative, LocalTime time, boolean grounded) {
        String ts = SUMMARY_TIME_FMT.format(time);
        String[] parts = narrative.split(CALENDAR_MARKER, 2);
        String marketBody = parts[0].strip();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🌐 **현재 시황·시장상황** | %s%n%n%s", ts, marketBody));
        if (grounded) {
            String calendarBody = parts.length > 1 ? parts[1].strip() : "";
            if (!calendarBody.isEmpty()) {
                sb.append(String.format("%n%n📅 **캘린더** | %s%n%n%s", ts, calendarBody));
            }
        } else {
            // 검색 없이 만든 날짜는 신뢰 불가 → 캘린더 생략. 사유를 남겨 "왜 캘린더가 없지?"를 방지.
            sb.append(String.format("%n%n📅 **캘린더** | 실시간 검색 미가용(API 한도)으로 생략"));
        }
        return sb.toString();
    }

    /**
     * 수급 기반 LLM 분석에 넣을 '실측 데이터' 블록을 만든다(전송·호출 없음 — 순수 함수, 테스트용).
     * 미선물 한 줄(있으면) → 외국인/기관 순매수·순매도(종목명(업종) +금액, TOP) → 외국인+기관 양매수 →
     * 거래대금 섹터 랭킹(있으면) 순. 업종은 sectorByCode에서 붙이되 미상·미분류면 생략한다.
     */
    static String buildFlowFacts(String indexLine, String marketFlowLine, String fxLine, String usFuturesLine,
                                 List<InvestorFlowItem> frgnBuys, List<InvestorFlowItem> frgnSells,
                                 List<InvestorFlowItem> instBuys, List<InvestorFlowItem> instSells,
                                 List<InvestorPairItem> dualBuy, String turnoverRanking,
                                 Map<String, String> sectorByCode) {
        StringBuilder sb = new StringBuilder();
        // 인과 사슬 순서로 맨 앞에: 지수(결과) → 시장 전체 수급(누가 사고파나) → 환율·미선물(대외 원인) → 그 뒤 종목별 수급.
        boolean hasMarket = false;
        if (indexLine != null) { sb.append(indexLine).append("\n"); hasMarket = true; }
        if (marketFlowLine != null) { sb.append(marketFlowLine).append("\n"); hasMarket = true; }
        if (fxLine != null) { sb.append(fxLine).append("\n"); hasMarket = true; }
        if (usFuturesLine != null) { sb.append(usFuturesLine).append("\n"); hasMarket = true; }
        if (hasMarket) sb.append("\n");
        sb.append("[외국인] 순매수: ").append(flowLine(frgnBuys, sectorByCode));
        sb.append("\n         순매도: ").append(flowLine(frgnSells, sectorByCode));
        sb.append("\n[기관] 순매수: ").append(flowLine(instBuys, sectorByCode));
        sb.append("\n       순매도: ").append(flowLine(instSells, sectorByCode));
        if (!dualBuy.isEmpty()) {
            String dual = dualBuy.stream()
                    .map(it -> it.name() + sectorTag(it.code(), sectorByCode))
                    .collect(Collectors.joining(", "));
            sb.append("\n[외국인+기관 양매수] ").append(dual);
        }
        if (turnoverRanking != null) sb.append("\n\n").append(turnoverRanking);
        return sb.toString();
    }

    /** 수급 한 줄 — "종목명(업종) +금액, ..." 형태. 비면 "(없음)". */
    private static String flowLine(List<InvestorFlowItem> items, Map<String, String> sectorByCode) {
        if (items.isEmpty()) return "(없음)";
        return items.stream()
                .map(it -> it.name() + sectorTag(it.code(), sectorByCode) + " " + KisMoney.formatNetWon(it.netValueWon()))
                .collect(Collectors.joining(", "));
    }

    /** 종목 업종을 "(반도체)"처럼 괄호로. 미상·미분류면 빈 문자열(괄호 생략). */
    private static String sectorTag(String code, Map<String, String> sectorByCode) {
        String s = sectorByCode.get(code);
        return (s != null && !s.isBlank() && !SectorCacheStore.UNCLASSIFIED.equals(s)) ? "(" + s + ")" : "";
    }

    /**
     * 지난 리포트 주기 동안 수집된 뉴스 헤드라인을 LLM 입력용 블록으로 만든다(순수 함수 — 전송 없음, 테스트용).
     * 정규화 제목(대괄호·문장부호·공백 제거) 기준으로 중복을 접어 토큰을 아끼고, 최대 max건만 남긴다.
     * 각 줄은 "HH:mm 출처 | 제목"(발행 시각을 모르면 시각 생략). 헤드라인이 없으면 null → 섹션 생략.
     */
    static String buildNewsHeadlines(List<NewsArticle> articles, int max) {
        if (articles == null || articles.isEmpty()) return null;
        LinkedHashMap<String, NewsArticle> unique = new LinkedHashMap<>();
        for (NewsArticle a : articles) {
            String key = normalizeTitle(a.title());
            if (key.isEmpty()) continue;
            unique.putIfAbsent(key, a);
            if (unique.size() >= max) break;
        }
        if (unique.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[지난 1시간 주요 뉴스 헤드라인]");
        for (NewsArticle a : unique.values()) {
            sb.append("\n");
            if (a.publishedAt() != null) {
                sb.append(SUMMARY_TIME_FMT.format(a.publishedAt().withZoneSameInstant(KST))).append(" ");
            }
            sb.append(a.source()).append(" | ").append(a.title());
        }
        return sb.toString();
    }

    /**
     * 제목 dedup 키 — 대괄호·꺾쇠·괄호 구간을 '내용째' 지운 뒤 남은 문장부호·공백을 제거하고 소문자화한다.
     * 말머리를 통째로 없애야 "[속보] A사, 5조원 수주"(RSS) ≈ "A사 5조원 수주"(네이버)가 같은 키로 접힌다.
     */
    private static String normalizeTitle(String title) {
        if (title == null) return "";
        String stripped = title
                .replaceAll("\\[[^\\]]*\\]", "")               // [속보] 등 대괄호 구간 통째 제거
                .replaceAll("<[^>]*>", "")                     // <특보>
                .replaceAll("\\([^)]*\\)", "")                 // (플래시)
                .replaceAll("\\uFF08[^\\uFF09]*\\uFF09", "")   // 전각 괄호 （ ）
                .replaceAll("\\u3010[^\\u3011]*\\u3011", "");   // 【 】
        return stripped.replaceAll("[\\s\"'\\u201C\\u201D\\u00B7,.\\-\\u2026]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static final String MACRO_SYSTEM_PROMPT =
            "너는 한국 주식시장 시황 분석가다. 아래 '국내 실측 데이터'(국내 지수 코스피·코스닥 등락, "
            + "시장 전체 외국인·기관 순매수(가집계), 원달러 환율, 미국 선물 등락률, 외국인·기관 수급 상위·업종, "
            + "외국인+기관 양매수, 거래대금 섹터 랭킹)와 '지난 1시간 주요 뉴스 헤드라인'(있으면)은 사실이다. "
            + "여기에 더해 Google 검색으로 ① 오늘 개별 종목·섹터가 오르거나 내린 실제 촉매(ADR 편입·상장, "
            + "수주·실적·신약·규제·M&A, 해외 증시·미국 선물이 움직인 이유) ② 입력의 '[오늘]' 이후(오늘 포함) "
            + "다음 주말까지 아직 지나지 않은 주요 일정을 검색으로 날짜까지 확인해 찾아라 — 뻔한 매크로만이 아니라 "
            + "개별 기업 실적 발표(미국 빅테크·반도체, 한국 주요 종목은 종목명과 날짜까지), 경제지표(CPI·PCE·고용·PMI·소매판매), "
            + "중앙은행(FOMC·한은 금통위·ECB·BOJ), 옵션·선물 만기(쿼드러플위칭), 지수 리밸런싱(MSCI·FTSE·코스피200), "
            + "배당락, IPO·보호예수 해제, 국내외 증시 공휴일·휴장. 오늘보다 과거인 일정은 넣지 마라. "
            + "그런 다음 아래 두 블록으로만, 군더더기 없이 콤팩트하게 한국어로 답한다. 다른 제목·머리말·인사말은 절대 쓰지 마라.\n"
            + "[블록1] 정확히 3개 번호 항목(각 1~2문장):\n"
            + "1. 지수: 코스피·코스닥 방향과 오늘 주도 섹터를 한 줄로. 둘이 엇갈리면(디버전스) 그 점을 짚는다.\n"
            + "2. 오늘 촉매: 오늘 특정 종목/섹터가 움직인 '이유'를 검색으로 확인해 1~2개만(예: 하이닉스 ADR 편입일이라 상승). "
            + "정치·단순 지수 급락처럼 종목 선택에 도움 안 되는 뻔한 내용은 빼고, 특정 종목 주가에 실제 영향을 준 이벤트만.\n"
            + "3. 수급: 외국인·기관이 각각 사고/파는 대표 종목과 방향을 1줄로 요약한다(길게 나열하지 마라).\n"
            + "[구분] 블록1이 끝나면 한 줄에 정확히 @@CAL@@ 만 출력한다.\n"
            + "[블록2] 캘린더: '[오늘]' 이후(오늘 포함) 다음 주말까지의 일정을 '•' 불릿으로 날짜순 최대 6~8개 짚는다. "
            + "각 불릿 형식: 'YYYY-MM-DD(요일) 이벤트 — 한 줄 의미 (근거: 매체/페이지에서 본 날짜 문구)'. "
            + "날짜 검증 절차를 반드시 지켜라 — (가) 후보 일정마다 google_search로 그 이벤트의 정확한 날짜를 찾는다 "
            + "(나) 검색 결과 텍스트에 그 날짜가 실제로 적혀 있는지 확인한다 (다) 확인된 날짜만 쓰고, 근거 문구를 괄호로 단다 "
            + "(라) 검색에서 정확한 날짜를 문자 그대로 확인하지 못했으면 그 항목은 반드시 삭제한다(패턴·관례로 어림잡기 절대 금지). "
            + "실적은 '이미 발표됐는지'를 검색으로 확인해 오늘 이전에 발표된 건 넣지 마라. "
            + "이미 다 아는 뻔한 매크로보다 개별 기업 실적일·리밸런싱·만기 같은 덜 알려진 구체 일정을 우선한다. "
            + "억지로 개수를 채우지 말고 검증을 통과한 것만 있는 만큼 쓰며, 하나도 없으면 '검색으로 확인된 예정 일정 없음'이라고만 쓴다.\n"
            + "검색으로 확인되지 않은 추측·소문·헤드라인에 없는 사실은 쓰지 마라. 과장·투자권유 금지.";

    /** 시황 분석 응답에서 '시황·시장상황' 블록과 '캘린더' 블록을 가르는 구분자(프롬프트가 이 토큰만 한 줄 출력). */
    private static final String CALENDAR_MARKER = "@@CAL@@";

    private static final String MACRO_USER_PROMPT =
            "아래는 지금 국내 지수·환율·미국 선물·수급 실측과, 지난 1시간 동안 수집된 뉴스 헤드라인이다. "
            + "위 형식(블록1: 1.지수 → 2.오늘 촉매 → 3.수급 1줄, 그다음 @@CAL@@ 한 줄, 블록2: 캘린더 불릿)대로, "
            + "검색을 활용해 오늘 시장이 왜 이렇게 움직이는지와 앞으로 봐야 할 일정만 핵심으로 짚어줘.";
}
