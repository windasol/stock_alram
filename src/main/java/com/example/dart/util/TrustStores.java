package com.example.dart.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Locale;

/**
 * 프로젝트 공용 SSLContext.
 *
 * 회사망 등 SSL 검사 프록시 환경에서는 사설 루트 CA가 Windows 인증서 저장소에만 설치되고
 * JDK cacerts에는 없어 PKIX 검증이 실패한다. Windows에서는 OS 저장소(Windows-ROOT)를
 * 신뢰 저장소로 쓰는 SSLContext를 만들어 이를 해결한다. 그 외 OS·실패 시 JDK 기본값.
 */
public final class TrustStores {

    private static final Logger log = LoggerFactory.getLogger(TrustStores.class);
    private static final SSLContext SYSTEM = create();
    /** 외부 API 호출용 공용 연결 타임아웃(초). */
    private static final int CONNECT_TIMEOUT_SEC = 10;

    private TrustStores() {}

    public static SSLContext systemDefault() {
        return SYSTEM;
    }

    /**
     * 프로젝트 공용 HttpClient 빌더 — SSL 검사 프록시 대응 신뢰저장소 + 표준 연결 타임아웃(10초)이
     * 미리 설정돼 있다. 리다이렉트 등 추가 설정이 필요하면 반환된 빌더에 이어 붙인 뒤 {@code build()} 한다.
     */
    public static HttpClient.Builder newHttpClientBuilder() {
        return HttpClient.newBuilder()
                .sslContext(SYSTEM)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC));
    }

    /** 추가 설정이 필요 없는 경우의 편의 팩토리 — {@link #newHttpClientBuilder()}.build(). */
    public static HttpClient newHttpClient() {
        return newHttpClientBuilder().build();
    }

    private static SSLContext create() {
        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        if (!isWindows) return defaultContext();

        try {
            KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
            windowsRoot.load(null, null);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(windowsRoot);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            log.info("Windows 인증서 저장소(Windows-ROOT)를 신뢰 저장소로 사용");
            return ctx;
        } catch (Exception e) {
            log.warn("Windows-ROOT 신뢰 저장소 초기화 실패 — JDK 기본 truststore 사용", e);
            return defaultContext();
        }
    }

    private static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("기본 SSLContext 초기화 실패", e);
        }
    }
}
