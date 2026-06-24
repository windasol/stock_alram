package com.example.dart.news;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakingNewsTest {

    private static final BreakingNews BREAKING =
            new BreakingNews(List.of("속보", "긴급", "특보", "플래시", "브레이킹"));

    @Test
    void 대괄호_속보_말머리를_잡는다() {
        assertTrue(BREAKING.isBreaking("[속보] 삼성전자, 2조 규모 공급계약"));
    }

    @Test
    void 다양한_괄호와_키워드_말머리를_잡는다() {
        assertTrue(BREAKING.isBreaking("<속보> 코스피 3000 돌파"));
        assertTrue(BREAKING.isBreaking("(속보) 금통위 기준금리 동결"));
        assertTrue(BREAKING.isBreaking("【속보】 환율 1400원 돌파"));
        assertTrue(BREAKING.isBreaking("[긴급] 거래소 시스템 장애"));
        assertTrue(BREAKING.isBreaking("[특보] 태풍 상륙"));
        assertTrue(BREAKING.isBreaking("[브레이킹] 美 금리 인하"));
    }

    @Test
    void 괄호_안에_키워드가_섞여있어도_잡는다() {
        assertTrue(BREAKING.isBreaking("[긴급속보] 후속 보도"));
        assertTrue(BREAKING.isBreaking("[속보2보] 추가 피해 확인"));
        assertTrue(BREAKING.isBreaking("[ 속보 ] 공백 포함"));
    }

    @Test
    void 보도_차수_말머리를_자동으로_잡는다() {
        assertTrue(BREAKING.isBreaking("[1보] 1차 속보"));
        assertTrue(BREAKING.isBreaking("[2보] 후속 보도"));
        assertTrue(BREAKING.isBreaking("<3보> 상세 보도"));
    }

    @Test
    void 정리성_종합_말머리는_거른다() {
        // [종합2보]는 차수 앞에 '종합'이 붙어 차수 패턴에 걸리지 않고, 키워드도 아니다.
        assertFalse(BREAKING.isBreaking("[종합2보] 사건 정리"));
        assertFalse(BREAKING.isBreaking("[종합] 하루 시황"));
    }

    @Test
    void 기본에_없는_단독은_거른다() {
        // 단독은 속보성이 아니라 기본 키워드에 없다 — 필요하면 설정에 추가.
        assertFalse(BREAKING.isBreaking("[단독] 대기업 인수 추진"));
    }

    @Test
    void 속보_말머리가_없으면_거른다() {
        assertFalse(BREAKING.isBreaking("삼성전자 신제품 출시"));
        assertFalse(BREAKING.isBreaking("코스피 강보합 마감"));
    }

    @Test
    void 괄호로_감싸지_않은_본문_속_긴급은_무시한다() {
        assertFalse(BREAKING.isBreaking("기업들 긴급 점검 회의 소집"));
        assertFalse(BREAKING.isBreaking("정부, 속보성 대응 방안 발표"));
    }

    @Test
    void null은_거른다() {
        assertFalse(BREAKING.isBreaking(null));
    }

    @Test
    void 설정에_추가하면_단독도_잡는다() {
        BreakingNews withScoop = new BreakingNews(List.of("속보", "단독"));
        assertTrue(withScoop.isBreaking("[단독] 대기업 인수 추진"));
    }
}
