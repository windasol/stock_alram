package com.example.dart.kis.application;

import com.example.dart.common.domain.KstTime;
import com.example.dart.kis.domain.VolumeRankItem;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 변동성 급등 1건을 알림 메시지로 조립한다. */
public class KisAlertComposer {

    private static final DateTimeFormatter DETECT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId KST = KstTime.ZONE;

    /** @param session 감지 시점의 시장 세션(정규장 / NXT 프리·애프터마켓) — KisPollerService.sessionLabel */
    public String compose(VolumeRankItem it, String session) {
        return String.format(
                "🚨 **급등** | %s (%s)\n"
                        + "📈 %+.1f%% · 현재가 %,d원 · 거래량 %,d주\n"
                        + "🕒 %s · 감지 %s\n%s",
                it.name(), it.code(),
                it.changePct(), it.price(), it.acmlVol(),
                session, DETECT_TIME_FMT.format(ZonedDateTime.now(KST)),
                naverUrl(it.code()));
    }

    private static String naverUrl(String code) {
        return "https://m.stock.naver.com/domestic/stock/" + code + "/total";
    }
}
