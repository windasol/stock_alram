package com.example.dart.kis;

import java.time.LocalTime;

/**
 * KIS 당일 1분봉 한 개(inquire-time-itemchartprice output2의 한 행).
 * 공시 후 주가추적이 [공시-2분 ~ 공시+10분] 창을 한 번에 분석하는 데 쓴다.
 *
 * @param time  체결 시각(stck_cntg_hour, 분 단위)
 * @param open  시가(stck_oprc, 원)
 * @param high  고가(stck_hgpr, 원)
 * @param low   저가(stck_lwpr, 원)
 * @param close 종가/현재가(stck_prpr, 원)
 */
public record MinuteCandle(LocalTime time, long open, long high, long low, long close) {}
