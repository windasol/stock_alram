package com.example.dart.kis.application;

import com.example.dart.kis.domain.VolumeRankItem;
import com.example.dart.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 급등 종목 정찰 — 등락률순위 스냅샷에서 임계(기본 10%) 이상 오른 종목을 골라 알린다.
 * 같은 종목 반복 알림은 쿨다운으로 억제한다. 스냅샷 조회·백오프는 폴러(KisPollerService)가 담당.
 */
public class GainerScout {

    private static final Logger log = LoggerFactory.getLogger(GainerScout.class);

    private final Notifier notifier;
    private final KisAlertComposer alertComposer;
    private final double minChangePct;
    private final Duration cooldown;

    /** 종목코드 → 마지막 알림 시각. 쿨다운 내 재알림 억제. */
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    public GainerScout(Notifier notifier, KisAlertComposer alertComposer, double minChangePct, Duration cooldown) {
        this.notifier = notifier;
        this.alertComposer = alertComposer;
        this.minChangePct = minChangePct;
        this.cooldown = cooldown;
    }

    /** 스냅샷에서 임계 이상 급등 종목을 골라(쿨다운 통과분만) 알림을 보낸다. */
    public void alert(List<VolumeRankItem> items, String sessionLabel, Instant now) {
        for (VolumeRankItem it : items) {
            if (!isBigGainer(it, minChangePct)) continue;
            if (!cooledDown(it.code(), now)) continue;
            lastAlert.put(it.code(), now);
            log.info("급등 [{} {} {}]: {}%", sessionLabel, it.name(), it.code(), it.changePct());
            notifier.trySend(alertComposer.compose(it, sessionLabel),
                    "급등 알림 전송 실패: {} {}", it.name(), it.code());
        }
    }

    /** 전일 대비 등락률이 임계 이상인 "오늘 많이 오른" 종목인지. (순수 함수 — 테스트용) */
    static boolean isBigGainer(VolumeRankItem it, double minChangePct) {
        return it.changePct() >= minChangePct;
    }

    private boolean cooledDown(String code, Instant now) {
        Instant last = lastAlert.get(code);
        return last == null || Duration.between(last, now).compareTo(cooldown) >= 0;
    }
}
