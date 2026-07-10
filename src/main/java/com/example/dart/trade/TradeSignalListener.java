package com.example.dart.trade;

import java.util.OptionalLong;

/**
 * 공시 보강(계약금액·매출액·비율 확정) 직후 자동매매로 넘기는 트리거 신호.
 *
 * 공시 알림 파이프라인(DART {@code AlertComposer} / KIND {@code KindPollerService})이 계약 규모를 계산한
 * 그 지점에서 호출한다. 자동매매가 비활성이면 리스너는 null이라 아무 일도 일어나지 않는다(기존 알림 흐름 무변화).
 * 임계(≥N%) 판정·중복·한도 등 매매 결정은 전적으로 구현체(AutoTradeService)가 한다.
 *
 * DART/KIND 두 소스를 모두 받기 위해 도메인 모델이 아닌 원시값으로 넘긴다.
 */
public interface TradeSignalListener {

    /**
     * 수주·공급계약 호재 1건의 규모가 확정됐을 때 호출.
     *
     * @param signalId      중복 진입 방지용 고유 키(DART 접수번호 rcept_no 또는 KIND acptNo)
     * @param corpName      회사명(알림 표기용)
     * @param stockCode     6자리 종목코드(자동매매 대상)
     * @param contractWon   계약금액(원)
     * @param revenueWon    최근 매출액(원) — 없을 수 있음
     * @param salesRatioPct 매출액 대비 비율(%) — 공시 명시값 우선, 없으면 계약÷매출로 계산된 값
     */
    void onContractSignal(String signalId, String corpName, String stockCode, long contractWon,
                          OptionalLong revenueWon, double salesRatioPct);
}
