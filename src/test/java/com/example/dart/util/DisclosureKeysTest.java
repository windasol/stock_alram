package com.example.dart.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DisclosureKeysTest {

    @Test
    void DART와_KIND의_표기_변형이_같은_키가_된다() {
        // 가운뎃점·공백 변형
        assertEquals(
                DisclosureKeys.of("삼성전자", "단일판매ㆍ공급계약체결"),
                DisclosureKeys.of("삼성전자", "단일판매 · 공급계약체결"));
        // 대괄호 접두어 제거
        assertEquals(
                DisclosureKeys.of("삼성전자", "[자율공시] 단일판매ㆍ공급계약체결"),
                DisclosureKeys.of("삼성전자", "단일판매ㆍ공급계약체결"));
        // 영문 대소문자
        assertEquals(
                DisclosureKeys.of("셀트리온", "fda승인"),
                DisclosureKeys.of("셀트리온", "FDA승인"));
    }

    @Test
    void 회사나_제목이_다르면_다른_키() {
        assertNotEquals(
                DisclosureKeys.of("삼성전자", "공급계약체결"),
                DisclosureKeys.of("LG전자", "공급계약체결"));
        assertNotEquals(
                DisclosureKeys.of("삼성전자", "공급계약체결"),
                DisclosureKeys.of("삼성전자", "무상증자결정"));
    }

    @Test
    void null_입력에도_안전하다() {
        assertEquals("|", DisclosureKeys.of(null, null));
    }
}
