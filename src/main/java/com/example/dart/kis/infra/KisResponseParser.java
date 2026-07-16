package com.example.dart.kis.infra;

import com.example.dart.common.infra.HttpJson;
import com.example.dart.kis.domain.Investor;
import com.example.dart.kis.domain.InvestorConfirmed;
import com.example.dart.kis.domain.InvestorFlowItem;
import com.example.dart.kis.domain.InvestorPairItem;
import com.example.dart.kis.domain.MinuteCandle;
import com.example.dart.kis.domain.TradingValueItem;
import com.example.dart.kis.domain.VolumeRankItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

/**
 * KIS Open API 응답(JSON 문자열)을 도메인 record로 옮기는 순수 파싱 계층 — 네트워크·HTTP와 분리돼 있어
 * 단위 테스트가 쉽다. {@link KisClient}는 조회(HTTP)만 하고, 응답 해석은 전부 여기에 위임한다.
 *
 * <p>모든 메서드는 방어적이다 — {@code rt_cd!="0"}(거부)·형식 이상·JSON 깨짐이면 로그를 남기고
 * 빈 목록/empty/null을 돌려준다(호출부의 폴링을 멈추지 않는다).
 */
final class KisResponseParser {

    private static final Logger log = LoggerFactory.getLogger(KisResponseParser.class);

    /**
     * 가집계 순매수 거래대금(frgn/orgn_ntby_tr_pbmn) 단위 — 원이 아니라 백만원이라 원으로 환산해 저장한다.
     * (실측: 응답값 6자리 ≈ 360,000 → 백만원 환산 시 3,600억으로 현실적. 원이면 36만원으로 비현실적.)
     * 단위가 다르게 확인되면 이 상수만 바꾸면 된다(예: 천원이면 1_000L).
     */
    static final long NTBY_PBMN_UNIT_WON = 1_000_000L;

    private KisResponseParser() {}

    /** 현재가 조회 응답에서 업종명(bstp_kor_isnm)만 추출한다. */
    static String parseSector(String json) {
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                // 비정상 응답을 조용히 삼키면 전 종목이 '미분류'로 떨어지는 원인을 못 찾는다 — rt_cd/msg를 드러낸다.
                // (예: EGW00201 초당 거래건수 초과 = 유량제한, 권한 미보유 등)
                log.warn("KIS 업종 조회 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return "";
            }
            return root.path("output").path("bstp_kor_isnm").asText("").trim();
        } catch (Exception e) {
            log.warn("KIS 업종 파싱 실패: {}", e.toString());
            return "";
        }
    }

    /**
     * 호가 조회 응답에서 매도1호가(askp1)·매수1호가(bidp1)를 뽑아 중간값(원)을 계산한다.
     * 둘 다 양수일 때만 의미가 있다 — 한쪽이라도 0(호가 비어있음·장 닫힘·미상장)이면 empty.
     */
    static OptionalLong parseAskingMid(String json) {
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 호가 조회 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return OptionalLong.empty();
            }
            JsonNode out = root.path("output1");
            long ask = parseLong(out.path("askp1").asText());
            long bid = parseLong(out.path("bidp1").asText());
            if (ask <= 0 || bid <= 0) return OptionalLong.empty();
            return OptionalLong.of((ask + bid) / 2);
        } catch (Exception e) {
            log.warn("KIS 호가 파싱 실패: {}", e.toString());
            return OptionalLong.empty();
        }
    }

    /**
     * 분봉 응답(output2)을 시각 오름차순 MinuteCandle 목록으로 파싱한다. KIS는 최신→과거 순으로 주므로
     * 마지막에 뒤집어 오름차순으로 돌려준다. rt_cd!="0"이면 빈 목록.
     */
    static List<MinuteCandle> parseMinuteCandles(String json) {
        List<MinuteCandle> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 분봉 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return result;
            }
            for (JsonNode n : root.path("output2")) {
                String hms = n.path("stck_cntg_hour").asText("");
                if (hms.length() < 6) continue;
                long close = parseLong(n.path("stck_prpr").asText());
                if (close <= 0) continue;   // 거래 없는 빈 봉(0)은 버린다
                LocalTime time = LocalTime.of(
                        Integer.parseInt(hms.substring(0, 2)),
                        Integer.parseInt(hms.substring(2, 4)),
                        Integer.parseInt(hms.substring(4, 6)));
                result.add(new MinuteCandle(time,
                        parseLong(n.path("stck_oprc").asText()),
                        parseLong(n.path("stck_hgpr").asText()),
                        parseLong(n.path("stck_lwpr").asText()),
                        close));
            }
            // KIS는 최신→과거 순. 분석은 시간순이 편하므로 오름차순으로 뒤집는다.
            Collections.reverse(result);
        } catch (Exception e) {
            log.warn("KIS 분봉 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /** 등락률순위 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록. */
    static List<VolumeRankItem> parseFluctuationRank(String json) {
        List<VolumeRankItem> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (isRejected(root, "등락률순위")) return result;
            for (JsonNode n : root.path("output")) {
                String code = n.path("stck_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new VolumeRankItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("stck_prpr").asText()),
                        parseDouble(n.path("prdy_ctrt").asText()),
                        parseLong(n.path("acml_vol").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 등락률순위 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 거래대금순위 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록.
     * volume-rank의 종목코드 필드는 mksc_shrn_iscd, 거래대금은 acml_tr_pbmn(원).
     */
    static List<TradingValueItem> parseVolumeRank(String json) {
        List<TradingValueItem> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (isRejected(root, "거래대금순위")) return result;
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new TradingValueItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("acml_tr_pbmn").asText()),
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 거래대금순위 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 외국인·기관 매매종목가집계 응답 JSON을 파싱한다. rt_cd!="0"이면 빈 목록.
     * 종목코드는 mksc_shrn_iscd, 순매수 거래대금은 투자자별 필드(외국인 frgn_ntby_tr_pbmn / 기관 orgn_ntby_tr_pbmn).
     */
    static List<InvestorFlowItem> parseInvestorFlow(String json, Investor inv) {
        List<InvestorFlowItem> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (isRejected(root, "외국인·기관 수급")) return result;
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new InvestorFlowItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path(inv.amountField()).asText()) * NTBY_PBMN_UNIT_WON,  // 백만원 → 원
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 외국인·기관 수급 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 동시매매 판정용 — 한 행에서 외국인·기관 순매수 거래대금을 함께 파싱한다. rt_cd!="0"이면 빈 목록.
     * 거래대금은 백만원→원으로 환산해 담는다.
     */
    static List<InvestorPairItem> parseInvestorPair(String json) {
        List<InvestorPairItem> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (isRejected(root, "동시매매 수급")) return result;
            for (JsonNode n : root.path("output")) {
                String code = n.path("mksc_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                result.add(new InvestorPairItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        parseLong(n.path("frgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,  // 백만원 → 원
                        parseLong(n.path("orgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 동시매매 수급 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * 외국계 매매종목 가집계(FHKST644100C0) 응답 파싱. rt_cd!="0"이면 빈 목록.
     * 종목코드 stck_shrn_iscd, 종목명 hts_kor_isnm. 순매수수량 = 외국계총매수(glob_total_shnu_qty) − 외국계총매도(glob_total_seln_qty),
     * 금액(원) ≈ 순매수수량 × 현재가(stck_prpr).
     */
    static List<InvestorFlowItem> parseForeignMemberEstimate(String json) {
        List<InvestorFlowItem> result = new ArrayList<>();
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (isRejected(root, "외국계 매매종목 가집계")) return result;
            for (JsonNode n : root.path("output")) {
                String code = n.path("stck_shrn_iscd").asText("").trim();
                if (code.isEmpty()) continue;
                long netQty = parseLong(n.path("glob_total_shnu_qty").asText())
                        - parseLong(n.path("glob_total_seln_qty").asText());   // 외국계 순매수수량
                long price = parseLong(n.path("stck_prpr").asText());
                result.add(new InvestorFlowItem(
                        code,
                        n.path("hts_kor_isnm").asText("").trim(),
                        netQty * price,                                        // 순매수수량 × 현재가 ≈ 순매수금액(원)
                        parseDouble(n.path("prdy_ctrt").asText())));
            }
        } catch (Exception e) {
            log.warn("KIS 외국계 매매종목 가집계 파싱 실패: {}", e.toString());
        }
        return result;
    }

    /**
     * inquire-investor 응답에서 당일(output[0]) 외국인·기관 순매수 거래대금을 읽는다.
     * 거래대금 단위는 가집계와 동일하게 백만원으로 보고 원으로 환산한다({@link #NTBY_PBMN_UNIT_WON}).
     * rt_cd!="0"이거나 output이 비면 null.
     */
    static InvestorConfirmed parseInvestorConfirmed(String json) {
        try {
            JsonNode root = HttpJson.MAPPER.readTree(json);
            if (!"0".equals(root.path("rt_cd").asText())) {
                log.warn("KIS 종목별 투자자 확정 수급 비정상 응답: rt_cd={} msg={}",
                        root.path("rt_cd").asText(), root.path("msg1").asText());
                return null;
            }
            JsonNode out = root.path("output");
            if (!out.isArray() || out.isEmpty()) return null;
            JsonNode row = out.get(0);   // 최신 영업일(마감 후=당일 확정)
            return new InvestorConfirmed(
                    row.path("stck_bsop_date").asText("").trim(),
                    parseLong(row.path("frgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON,
                    parseLong(row.path("orgn_ntby_tr_pbmn").asText()) * NTBY_PBMN_UNIT_WON);
        } catch (Exception e) {
            log.warn("KIS 종목별 투자자 확정 수급 파싱 실패: {}", e.toString());
            return null;
        }
    }

    /**
     * 랭킹 조회 응답의 성공 여부 판정 + 실패 로깅. rt_cd!="0"이면 라벨과 함께 비정상 응답을 남기고 true.
     * 시장구분/권한 거부(rt_cd=2 또는 FID_COND_MRKT_DIV_CODE)는 설정 오류라 error로 격상, 그 외는 warn.
     */
    private static boolean isRejected(JsonNode root, String label) {
        if ("0".equals(root.path("rt_cd").asText())) return false;
        String rtCd = root.path("rt_cd").asText();
        String msg = root.path("msg1").asText();
        if ("2".equals(rtCd) || msg.contains("FID_COND_MRKT_DIV_CODE")) {
            log.error("KIS {} 시장구분/권한 거부: rt_cd={} msg={} — 시장구분(J/NX/UN)·권한/도메인 확인", label, rtCd, msg);
        } else {
            log.warn("KIS {} 비정상 응답: rt_cd={} msg={}", label, rtCd, msg);
        }
        return true;
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        s = s.replaceAll("[,\\s]", "");
        if (s.isEmpty() || "-".equals(s)) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double parseDouble(String s) {
        if (s == null) return 0;
        s = s.replaceAll("[,\\s]", "");
        if (s.isEmpty() || "-".equals(s)) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
