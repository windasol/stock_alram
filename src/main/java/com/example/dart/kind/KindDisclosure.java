package com.example.dart.kind;

/**
 * KIND 오늘의공시 목록의 한 행.
 *
 * @param time      게시 시각 "HH:mm" (KST, 당일)
 * @param market    시장구분 — "유가증권", "코스닥", "코넥스"
 * @param company   회사명
 * @param title     공시 제목 (DART와 동일한 표준 양식명)
 * @param acptNo    KIND 접수번호 — 중복 제거 키
 * @param submitter 제출인
 */
public record KindDisclosure(
        String time,
        String market,
        String company,
        String title,
        String acptNo,
        String submitter
) {

    public String detailUrl() {
        return "https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno=" + acptNo;
    }

    /** DART corp_cls와 같은 시장 코드 — CORP_CLS 설정으로 공시·KIND를 일관되게 필터링한다. */
    public String marketCode() {
        return switch (market) {
            case "유가증권" -> "Y";
            case "코스닥"   -> "K";
            case "코넥스"   -> "N";
            default          -> "";
        };
    }
}
