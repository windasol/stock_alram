package com.example.dart.kis;

/** 거래대금 랭킹 표시 단위 — 종목명·KRX 업종·당일 거래대금(원). */
public record Turnover(String name, String sector, long valueWon) {}
