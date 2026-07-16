package com.example.dart.kis.domain;

/** 섹터 요약 표시 단위 — 종목명·KRX 업종·등락률(%). 업종은 요약 시점에 조회해 채운다. */
public record Gainer(String name, String sector, double changePct) {}
