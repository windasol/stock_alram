# 아키텍처 규칙 (Architecture Rules)

stock_alram의 구조 규칙 문서. **새 클래스 추가·패키지 이동·의존성 추가 전에 반드시 이 문서를 따른다.**
규칙 §3·§4는 `ArchitectureTest`(ArchUnit)로 기계 검증되며 `./gradlew test`에서 위반이 즉시 잡힌다.

---

## §1. 시스템 개요 + 컨텍스트 맵

한국 주식 공시(DART·KIND)/뉴스/수급 알림 봇. Java 21 + Gradle, **프레임워크 없음**(Spring·DI 컨테이너 없음).
실행 단위는 독립 폴러들이며, 각 폴러 = 자체 스레드 + 자체 상태파일 + 자체 활성화 플래그. DB 없이 작업 디렉터리의 평문/JSONL 파일로 영속화한다.

### 바운디드 컨텍스트

| 컨텍스트 | 패키지 | 담당 | 스레드 | 상태파일 | 활성 조건 |
|---|---|---|---|---|---|
| **공시 (disclosure)** | `disclosure/` | DART 폴링 + KIND 선행 폴링 + 호재 필터 + 규모 보강 | `dart-poller`, `kind-poller`, enrich 풀 | `seen.txt`, `seen_kind.txt`, `seen_disclosure_keys.txt` | DART 항상, KIND는 `KIND_ENABLED` |
| **주가 추적 (pricetrack)** | `pricetrack/` | 공시 후 주가 변동 통계 | `price-tracker` | `disclosure_price_stats.jsonl` | 항상 |
| **KIS 시장분석 (kis)** | `kis/` | 급등·거래대금·수급·시황 리포트 | `kis-poller` | `kis_sectors.txt`, `kis_token.txt` | `KIS_APP_KEY` 존재 |
| **뉴스 (news)** | `news/` | RSS/구글/네이버 헤드라인 수집 → 버퍼 | `news-poller` | `seen_news.txt` | `NEWS_ENABLED` |
| **자동매매 (trade)** | `trade/` | 계약 공시 신호 → 드라이런 매매 | `auto-trade` | (메모리) | `AUTO_TRADE_ENABLED` |
| **알림 (notify)** | `notify/` | Discord/Webex 발송 | 호출자 스레드 | — | 항상 |
| **LLM (llm)** | `llm/` | Gemini/Ollama 시황 분석 | 호출자 스레드 | — | `MARKET_REPORT_*` |

DART와 KIND는 별개 컨텍스트가 **아니다** — 같은 공시 도메인의 두 인바운드 소스 어댑터다.
둘은 "같은 공시를 먼저 잡은 쪽만 알림"이라는 단일 불변식(`seen_disclosure_keys.txt` 공유)을 지키므로 `disclosure/` 한 컨텍스트로 묶는다.

### 컨텍스트 간 허용 의존 (이 화살표만 허용)

```
disclosure ──(TradeSignalListener 포트만)──▶ trade
disclosure ──(DisclosurePriceTracker 공개 API)──▶ pricetrack
kis ──(NewsHeadlineBuffer 공개 API)──▶ news
pricetrack ──(공유 KisClient·MinuteCandle)──▶ kis
trade ──(공유 KisClient·MinuteCandle)──▶ kis
모든 컨텍스트 ──▶ notify, llm, common, config
App ──▶ 전부 (유일한 조립 루트)
```

pricetrack·trade의 kis 의존은 KIS 토큰 한도(분당 1회) 때문에 단일 `KisClient`를 공유해야 해서 생기는
의도된 의존이다 — kis의 application/infra 구체 구현이 아닌 `KisClient`(공유 어댑터)와 도메인 record만 쓴다.

- 위에 없는 컨텍스트 간 import는 **금지**.
- 타 컨텍스트의 `infra/` import는 **어떤 경우에도 금지** (공개 진입점은 application/domain 층에 있어야 한다).
- 모든 컨텍스트를 아는 클래스는 `App` 하나뿐이다.

## §2. 아키텍처 결정 (ADR)

**채택: 패키지-바이-컨텍스트 + 컨텍스트 내부 경량 헥사고날.**

컨텍스트별로 패키지를 나누고(수직 분리), 도메인 로직이 실재하는 컨텍스트만 내부를 `domain / application / infra` 3층으로 나눈다. 포트(인터페이스)는 교체 실익이 실재하는 외부 IO에만 둔다.

| 기각한 대안 | 이유 |
|---|---|
| 풀 헥사고날 (단일 코어 + 전 IO 포트) | 도메인이 7개 컨텍스트에 흩어진 작은 판정·집계 로직이라 단일 코어는 인위적 통합. 구현 1개짜리 클라이언트에 포트를 씌우는 건 세리머니 |
| 클린 아키텍처 (usecase/interactor 4계층) | 10k줄·무프레임워크·1인 개발에서 파일 수만 늘림. 이 프로젝트의 "record + 순수 static 함수" 스타일과 충돌 |
| 전통 레이어드 (수평 계층) | 이미 존재하는 수직(컨텍스트) 분리 자산을 파괴하고 God-layer(`service/`)를 재생산 |
| 루트 패키지 rename (`com.example.dart` → 다른 이름) | 전 파일 diff를 만드는 순수 비용. dart가 이름과 달리 컨텍스트 하나일 뿐이라는 어색함은 감수한다 |

이 결정을 재논쟁하려면 전제(규모·무프레임워크·1인 개발)가 바뀌었는지부터 확인할 것.

## §3. 의존 방향 규칙 (ArchUnit 검증)

컨텍스트 내부 3층의 의존은 **한 방향**:

```
infra ──▶ application ──▶ domain
```

1. `domain`은 같은 컨텍스트의 application/infra를 import할 수 없다. 다른 패키지 중에는 `common.domain`·`common.text`만 허용.
2. `application`은 같은 컨텍스트의 domain·infra와 `common`, 그리고 §1 허용 화살표의 대상 컨텍스트만 import할 수 있다.
   (application이 자기 컨텍스트 infra의 구체 클래스를 쓰는 것은 허용 — 포트 강제는 하지 않는다. §5 참조)
3. `common`(공유 커널)은 **어떤 컨텍스트도 import할 수 없다**. config도 import하지 않는다.
4. `config`는 `common`만 import할 수 있다.
5. 순환 의존 금지 — 컨텍스트 간에도, 층 간에도.

## §4. 도메인 순수성 규칙 (ArchUnit 검증)

`*/domain/` 패키지의 기본형은 **record + 순수 static 함수**다. 다음을 import/사용할 수 없다:

- `java.net.**` (HTTP 호출)
- `java.io.**`, `java.nio.file.**` (파일 IO)
- `org.jsoup.**` (HTML 파싱은 infra의 일)
- `org.slf4j.**` (로깅은 application부터 — 도메인 함수는 값을 반환할 뿐)
- `java.util.concurrent.**`, `Thread` (스케줄링·동시성은 application의 일)
- 예외: `com.fasterxml.jackson.annotation.**`(어노테이션만)은 허용 — DTO 겸용 record(`Disclosure` 등)의 매핑 표기는 IO가 아니다. `jackson-databind`(ObjectMapper)는 금지.

시간(`LocalTime.now()` 등)에 의존하는 판정은 **시각을 파라미터로 받는 순수 함수**로 쓴다 (예: `TradingSession.at(LocalTime)`). 지금 시각을 읽는 것은 호출자(application)의 일.

## §5. 포트(인터페이스) 규칙

**인터페이스는 구현이 2개가 되는 시점에 뽑는다. 그 전에는 구체 클래스를 직접 주입한다.**

현재 포트는 3개가 전부이며, 각각 교체 실익이 실재한다:

| 포트 | 구현 | 교체 실익 |
|---|---|---|
| `notify.Notifier` | DiscordService, WebexService | 설정으로 채널 선택 |
| `llm.LlmClient` | GeminiClient, OllamaClient | 설정으로 모델 선택 |
| `trade.TradeSignalListener` | AutoTradeService | 순환의존 회피 + 비활성 시 null |

`DartClient`·`KisClient`·`KindClient`·`RssClient` 등 API 클라이언트의 인터페이스화는 **금지**. 테스트 대체는 서브클래스 스텁으로 충분하다 (`AlertComposerTest` 참조).

## §6. 조립 규칙

- 의존성 있는 객체(클라이언트·서비스·폴러)의 `new`는 **`App`에서만**. 서비스가 자기 의존성을 필드 초기화식이나 생성자 내부에서 `new`하는 것은 금지.
  - 안티패턴 실례: `KisPollerService`가 `new UsFuturesClient()`를 필드에서 직접 생성해 테스트 대체·설정이 불가능했다 (Phase 3에서 해소).
- 무상태 순수 헬퍼(`DocumentParser`, `NewsFilter` 등 IO 없는 것)는 어디서 만들어도 무방하나, 공유 인스턴스가 있으면 주입받는다.
- 조건부 기능(KIND·뉴스·KIS·자동매매)은 App에서 config 플래그로 생성 여부를 결정하고, 미설정 시 **null 주입 + 호출측 null 가드**가 이 프로젝트의 관례다.
- `KisClient`는 토큰 발급 한도(분당 1회) 때문에 **단일 인스턴스를 전 컨텍스트가 공유**한다 — 새 KIS 소비자를 만들 때 새로 만들지 말고 주입받을 것.

## §7. 네이밍 규칙

| 접미사 | 의미 | 층 | 제약 |
|---|---|---|---|
| `*Client` | 외부 API 어댑터 | infra | HTTP/파싱만. 비즈니스 판정 금지 |
| `*PollerService` | 스케줄 루프 (스레드 소유) | application | `start()`/`stop()` 라이프사이클 |
| `*Composer`, `*Messages` | 메시지 문자열 조립 | domain/application | **IO 금지** — 네트워크를 타면 이름을 바꾸든 IO를 빼든 하라 |
| `*Store` | 파일 영속 | infra (common) | 파일 IO는 여기로 격리 |
| `*Tracker`, `*Enricher` | 상태 있는 오케스트레이션 | application | |

범용 `*Service`·`*Manager`·`*Util` 남발 금지 — 역할이 드러나는 이름을 먼저 찾는다.

## §8. 새 기능 추가 절차

1. **어느 컨텍스트인가?** 기존 컨텍스트에 속하면 그 패키지에. 새 컨텍스트면 3종 세트(자체 스레드 + 자체 SeenStore/상태파일 + `.env` 활성화 플래그)를 갖추고 §1 표에 행을 추가한다.
2. **판정·집계 로직은 순수 static 함수로 먼저** 쓰고 단위 테스트를 붙인다 (시각·입력은 파라미터로). 그 다음 폴러/서비스가 감싼다.
3. **조립은 App에** — 생성자 주입, 조건부면 null 관례를 따른다.
4. **메시지 조립은 Composer에** — 발송 로직(String.format 인라인)을 서비스 본문에 흩뿌리지 않는다.
5. **재시도·백오프·스케줄 보일러플레이트는 `common`의 것을 재사용** — 새로 만들기 전에 `PollWorker`/`RetryScheduler`가 커버하는지 확인.
6. 발송 전 확인: 알림 문자열이 바뀌는 변경이면 특성화 테스트(스냅샷)를 먼저 갱신한다.

## §9. 하지 않는 것 (과잉 설계 금지 목록)

| 금지 | 이유 |
|---|---|
| 클라이언트 전면 인터페이스화 | 구현 1개에 포트는 세리머니. 구현 2개째가 생기는 날 뽑는다 |
| Repository/영속성 포트 | `SeenStore`가 이미 충분한 추상화. 파일 1종에 포트 불필요. DB 이전 계획이 실재하면 그때 |
| 이벤트 버스 / 도메인 이벤트 | 발행자 2, 구독자 1인 시스템. `TradeSignalListener` 콜백 1개로 충분 |
| DI 컨테이너 (Spring/Guice) | `App.java` 수동 조립은 부채가 아니라 자산 — 조립이 한눈에 읽힌다 |
| CQRS, 애그리게잇 루트, 엔티티/VO 계층 강박 | record + 순수 static 함수가 이 도메인의 올바른 표현 |
| Gradle 멀티모듈 분리 | 10k줄에서는 패키지 규칙 + ArchUnit으로 충분 |
| 전 컨텍스트 3층 강제 | news/notify/llm처럼 어댑터 묶음에 가까운 컨텍스트는 평면 유지. 층은 코드가 요구할 때만 |

## §10. 위반 해소 이력

리팩토링 Phase 1~5로 아래 위반이 모두 해소됐다. **이 표가 비었는데 코드에 위반이 있다면 문서를
갱신하라 — 문서가 현실과 다르면 문서가 죽는다.** 현재 알려진 미해소 위반은 없다(ArchUnit 그린).

| # | 위반 | 해소 | 상태 |
|---|---|---|---|
| 1 | NXT 세션판정 중복 2벌, 폴러 백오프 3벌, enrichment 재시도 6벌, 분봉 UN→J 폴백 3벌 | Phase 1: `common`(TradingSession·PollWorker·PollBackoff·RetryScheduler)으로 통합, `KisClient.minuteCandlesWithFallback`로 폴백 흡수 | ✅ 해소 |
| 2 | 도메인 record(Gainer·Turnover·Stats·Position)가 서비스 내부에 숨음 | Phase 1: 컨텍스트 최상위(이후 Phase 2에서 domain 패키지)로 승격 | ✅ 해소 |
| 3 | 패키지가 컨텍스트 구조(§1)와 불일치 (`model/`·`service/`·`filter/`·`parse/` 등 수평 패키지 잔존) | Phase 2: 기계적 이동 + ArchUnit 6규칙으로 재발 방지 | ✅ 해소 |
| 4 | `KisPollerService` 1,274줄 God class (도메인+인프라+프레젠테이션+프롬프트 혼재), `market` 클라이언트 필드 `new` (§6 위반) | Phase 3: 파사드(216줄) + 협력자 7개로 분해, market 클라이언트 App 주입 전환 | ✅ 해소 |
| 5 | `AlertComposer`가 이름과 달리 fetch 오케스트레이션 수행 (§7 위반), Treasury/Trust/Cancellation 3중복 | Phase 4: `AlertMessages`(순수 조립) + `DisclosureEnricher`(오케스트레이션) 분리, 3중복을 단일 파이프라인으로 통합 | ✅ 해소 |
| 6 | `AppConfig` 64필드 God config — 어느 컨텍스트가 어떤 설정을 쓰는지 타입으로 안 드러남 | Phase 5: 컨텍스트별 중첩 record(Dart·Notify·News·Kind·Kis·Llm·Trade) 분해, 각 서비스는 자기 서브 config만 수령 | ✅ 해소 |
| 7 | HTTP+JSON 보일러플레이트 중복 — 클라이언트 9곳이 각자 `new ObjectMapper()`, GET 요청 조립·전송 블록 다수 반복 | Phase 6a: `common.infra.HttpJson`(공용 `MAPPER` + `get`/`getJson`)로 통합, `OllamaClient`도 공용 빌더(TrustStores) 경유로 일관화 | ✅ 해소 |
