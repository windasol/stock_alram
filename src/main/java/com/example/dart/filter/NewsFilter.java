package com.example.dart.filter;

import java.util.List;

public class NewsFilter {

    private static final List<String> GOOD_KEYWORDS = List.of(
            "단일판매", "공급계약", "수주", "자기주식취득", "자기주식 취득",
            "무상증자", "흑자전환", "품목허가", "임상", "승인",
            "합병", "최대주주변경", "전환사채", "신규시설투자",
            "유형자산취득", "타법인주식취득", "주식배당"
    );

    private static final List<String> EXCLUDE_KEYWORDS = List.of(
            "감자", "상장폐지", "거래정지", "횡령", "배임",
            "소송", "정정", "기재정정", "불성실공시",
            "관리종목", "투자주의", "환매"
    );

    public boolean isGoodNews(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) return false;

        for (String exclude : EXCLUDE_KEYWORDS) {
            if (reportNm.contains(exclude)) return false;
        }

        for (String good : GOOD_KEYWORDS) {
            if (reportNm.contains(good)) return true;
        }

        return false;
    }
}
