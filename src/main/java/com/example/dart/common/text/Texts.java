package com.example.dart.common.text;

/**
 * 문자열 표시용 공통 유틸 — 여러 클라이언트에 복사돼 있던 "길면 잘라 말줄임" 로직을 한곳으로 모은다.
 */
public final class Texts {

    private Texts() {}

    /**
     * {@code s}가 {@code max}자를 넘으면 앞 {@code max}자만 남기고 말줄임표(…)를 붙인다. 넘지 않으면 원문 그대로.
     * {@code null}은 빈 문자열로 취급한다. (LLM 프롬프트 축약·로그 본문 자르기 등에 공용)
     */
    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
