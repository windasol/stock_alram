package com.example.dart.common.domain;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 금액 표기 ↔ 원(long) 변환.
 *
 * 공시 본문의 계약금액·매출액은 보통 생 숫자("234,000,000,000")로, 네이버 시총은
 * 단위 혼합("1,970조 1,959억")으로 들어온다. 두 형태를 모두 원 단위로 파싱한다.
 */
public final class KoreanMoney {

    private KoreanMoney() {}

    private static final long JO  = 1_000_000_000_000L;
    private static final long UK  = 100_000_000L;
    private static final long MAN = 10_000L;

    /**
     * 숫자 + 선택 단위(조/억/만/원). 단위가 없으면 원으로 본다.
     * 단위는 숫자에 "붙어 있을 때만" 인정한다(숫자와 단위 사이 공백 불허) — 그래야
     * "확정 계약금액 5,541,200,000 조건부 …"에서 공백 뒤 '조건부'의 '조'를 조(兆) 단위로
     * 오인해 천문학적 금액으로 폭주하는 것을 막는다. 실제 표기는 "1,970조"·"2,340억원"처럼
     * 단위가 숫자에 붙고, "1,970조 1,959억"의 토큰 사이 공백은 아래 연속성 로직이 처리한다.
     */
    private static final Pattern TOKEN = Pattern.compile("([0-9][0-9,]*)(조|억|만|원)?");

    /**
     * "1,970조 1,959억", "2,340억원", "234,000,000,000", "5,000만원" 등을 원 단위로 파싱.
     * 첫 숫자부터 공백만으로 이어지는 금액 토큰까지만 합산하고, 다른 텍스트를 만나면 멈춘다
     * (추출값 뒤에 붙는 다른 항목 텍스트를 잘못 더하지 않도록).
     *
     * @return 파싱된 원 금액. 숫자가 없으면 empty.
     */
    public static OptionalLong parseWon(String text) {
        if (text == null || text.isBlank()) return OptionalLong.empty();
        Matcher m = TOKEN.matcher(text);
        long total = 0;
        boolean any = false;
        int lastEnd = 0;
        while (m.find()) {
            if (any && !text.substring(lastEnd, m.start()).isBlank()) break;  // 금액 토큰 연속성 끊기면 중단
            long num = Long.parseLong(m.group(1).replace(",", ""));
            String unit = m.group(2);
            long mult = unit == null ? 1L : switch (unit) {
                case "조" -> JO;
                case "억" -> UK;
                case "만" -> MAN;
                default  -> 1L;  // 원
            };
            total += num * mult;
            any = true;
            lastEnd = m.end();
        }
        return any ? OptionalLong.of(total) : OptionalLong.empty();
    }

    /** 원 → 사람이 읽는 단위 표기 ("1,970조 1,959억", "2,340억", "3,500만", "1,200원"). */
    public static String format(long won) {
        if (won < MAN) return String.format("%,d원", won);
        long jo = won / JO;
        long uk = (won % JO) / UK;
        if (jo > 0) return uk > 0 ? String.format("%,d조 %,d억", jo, uk) : String.format("%,d조", jo);
        if (uk > 0) return String.format("%,d억", uk);
        return String.format("%,d만", won / MAN);
    }
}
