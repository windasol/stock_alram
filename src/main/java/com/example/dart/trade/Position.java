package com.example.dart.trade;

import java.time.ZonedDateTime;

/** 열린(모의) 포지션 한 건 + 매매 판정 순수 함수. */
public record Position(String stockCode, String corpName, long entryPrice, long qty,
                       ZonedDateTime entryAt, double salesRatioPct) {

    public long budget() {
        return entryPrice * qty;
    }

    /** 예산으로 살 수 있는 정수 주식 수. 가격이 0 이하면 0. */
    public static long qtyFor(long budgetWon, long price) {
        return price <= 0 ? 0 : budgetWon / price;
    }

    /** 진입가 대비 손익률(%). */
    public static double pnlPct(long entryPrice, long currentPrice) {
        return entryPrice <= 0 ? 0.0 : (currentPrice - entryPrice) * 100.0 / entryPrice;
    }
}
