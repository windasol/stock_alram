package com.example.dart.common.text;

/**
 * 코드블록(고정폭) 표 정렬용 표시폭 유틸 — 한글 등 전각 문자를 폭 2로 계산해 자르고 패딩한다.
 * Webex/Discord 마크다운은 표(| |)를 렌더하지 않으므로 ASCII 정렬 표를 만들 때 쓴다.
 */
public final class TextTable {

    private TextTable() {}

    /**
     * 표시폭(한글 등 전각=2, 그 외=1) 기준으로 문자열을 자르고 공백 패딩한다.
     * @param left true면 좌측정렬(뒤에 공백), false면 우측정렬(앞에 공백).
     */
    public static String padDisplay(String s, int width, boolean left) {
        if (s == null) s = "";
        // 폭을 넘으면 표시폭 기준으로 자른다(마지막 전각 문자가 폭을 넘기지 않도록).
        int w = 0;
        StringBuilder cut = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = isWideChar(c) ? 2 : 1;
            if (w + cw > width) break;
            cut.append(c);
            w += cw;
        }
        String body = cut.toString();
        String pad = " ".repeat(Math.max(0, width - w));
        return left ? body + pad : pad + body;
    }

    /** 전각(표시폭 2) 문자인지 — 한글 음절·자모, CJK, 전각기호 등. (이모지는 폭이 들쭉날쭉해 표 본문엔 쓰지 않는다) */
    public static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F)    // 한글 자모
                || (c >= 0x2E80 && c <= 0xA4CF) // CJK 부수~한자
                || (c >= 0xAC00 && c <= 0xD7A3) // 한글 음절
                || (c >= 0xF900 && c <= 0xFAFF) // CJK 호환 한자
                || (c >= 0xFF00 && c <= 0xFF60) // 전각 영숫자·기호
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }
}
