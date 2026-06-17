package com.example.dart.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DisclosureKeysTest {

    private static final String D = "20260617";

    @Test
    void DART와_KIND의_표기_변형이_같은_키가_된다() {
        // 가운뎃점·공백 변형
        assertEquals(
                DisclosureKeys.of(D, "삼성전자", "단일판매ㆍ공급계약체결"),
                DisclosureKeys.of(D, "삼성전자", "단일판매 · 공급계약체결"));
        // 대괄호 접두어 제거
        assertEquals(
                DisclosureKeys.of(D, "삼성전자", "[자율공시] 단일판매ㆍ공급계약체결"),
                DisclosureKeys.of(D, "삼성전자", "단일판매ㆍ공급계약체결"));
        // 영문 대소문자
        assertEquals(
                DisclosureKeys.of(D, "셀트리온", "fda승인"),
                DisclosureKeys.of(D, "셀트리온", "FDA승인"));
        // DART는 "주요사항보고서(…)"로 감싸지만 KIND는 안쪽 형태만 준다 — 같은 키가 돼야 교차 중복이 잡힌다.
        assertEquals(
                DisclosureKeys.of(D, "와이즈넛", "주요사항보고서(자기주식취득결정)"),
                DisclosureKeys.of(D, "와이즈넛", "자기주식 취득 결정"));
        assertEquals(
                DisclosureKeys.of(D, "와이즈넛", "[기재정정]주요사항보고서(주식소각결정)"),
                DisclosureKeys.of(D, "와이즈넛", "주식소각결정"));
    }

    @Test
    void 회사나_제목이_다르면_다른_키() {
        assertNotEquals(
                DisclosureKeys.of(D, "삼성전자", "공급계약체결"),
                DisclosureKeys.of(D, "LG전자", "공급계약체결"));
        assertNotEquals(
                DisclosureKeys.of(D, "삼성전자", "공급계약체결"),
                DisclosureKeys.of(D, "삼성전자", "무상증자결정"));
    }

    @Test
    void 날짜가_다르면_다른_키() {
        // 같은 회사·제목이라도 다른 날 공시는 중복이 아니다 (반복 공시 영구 차단 방지).
        assertNotEquals(
                DisclosureKeys.of("20260616", "대한전선", "단일판매ㆍ공급계약체결"),
                DisclosureKeys.of("20260617", "대한전선", "단일판매ㆍ공급계약체결"));
    }

    @Test
    void null_입력에도_안전하다() {
        assertEquals("||", DisclosureKeys.of(null, null, null));
    }
}
