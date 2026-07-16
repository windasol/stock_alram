package com.example.dart.kis.domain;

/**
 * 수급 랭킹의 투자자 구분(외국인/기관) — 표시 라벨과 KIS 가집계 API 매핑값을 함께 든다.
 * (etcCode·amountField는 KIS 응답 필드명이지만, enum 자체는 여러 도메인 record·표가 공유하는
 * 도메인 개념이라 여기 둔다 — KisClient에 두면 domain→infra 역참조가 생긴다.)
 */
public enum Investor {
    FOREIGN("1", "frgn_ntby_tr_pbmn", "🌍 외국인"),
    INSTITUTION("2", "orgn_ntby_tr_pbmn", "🏛 기관");

    private final String etcCode;
    private final String amountField;
    private final String label;

    Investor(String etcCode, String amountField, String label) {
        this.etcCode = etcCode;
        this.amountField = amountField;
        this.label = label;
    }

    /** 가집계(foreign-institution-total) 조회의 FID_ETC_CLS_CODE 값 — 1: 외국인, 2: 기관계. */
    public String etcCode() {
        return etcCode;
    }

    /** 가집계 응답에서 순매수 거래대금(백만원)을 읽을 필드명. */
    public String amountField() {
        return amountField;
    }

    public String label() {
        return label;
    }
}
