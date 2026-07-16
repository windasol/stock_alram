# DART 실시간 호재 알림 봇

DART(전자공시시스템)·KIND(거래소 공시) 최신 공시와 언론사 RSS·구글뉴스·네이버 뉴스를 병렬로 폴링해 호재성 이슈를 골라 Discord/Webex로 알림을 보내는 봇.

**설계 원칙 — 재현율 우선**: 이 봇의 목적은 호재 뉴스를 누구보다 빠르게, 최대한 많이 잡는 것이다.
애매하면 보내고, 명백한 악재·중복 신호만 거른다. (오탐 1건의 비용 << 호재 1건을 놓치는 비용)

## 동작 흐름

```
PollerService (7초 주기 폴링)
  → DartClient.fetchRecent()        오늘자 공시 목록 조회 (시장·유형 조합별 호출 후 병합)
  → SeenStore                       이미 처리한 공시(rcept_no) 스킵, seen.txt로 재시작에도 유지
  → NewsFilter.matchTitle()         Stage 1: 제목 룰 매칭 (네트워크 없음, 빠름)
  → NewsFilter.bodyRejectReason()   Stage 2: 본문 검증 (수주공급계약 한정 — 조건부·규모)
  → AlertComposer.compose()         시장(코스피/코스닥)·카테고리 포함 마크다운 메시지 조립
  → Notifier.send()                 Discord 또는 Webex로 전송
```

KIND 폴러는 같은 거래소 공시를 DART보다 먼저 잡는 **속도용 선행 소스**다 (별도 스레드, 기본 15초 주기):

```
KindPollerService (07:00~20:00 KST, 연속 실패 시 점진 백오프)
  → KindClient.fetchToday()         KIND 오늘의공시 목록 (화면용 AJAX, HTML 테이블 파싱)
  → SeenStore                       이미 처리한 공시(접수번호) 스킵, seen_kind.txt로 유지
  → NewsFilter.matchTitle()         DART와 같은 제목 룰 재사용 (본문 필터는 생략 — 속도 우선)
  → DisclosureKeys                  회사명+제목 정규화 키로 DART와 교차 중복 제거 — 먼저 잡은 쪽만 알림
  → KindAlertComposer.compose()     헤더만 즉시 전송 (상세는 KIND 뷰어 링크)
```

뉴스 폴러는 공시 폴러와 **독립된 스레드에서 병렬로** 동작한다.
속도 우선으로 소스를 계층화했다: **언론사 RSS 직접 폴링**(포털 색인 지연 없음, 키 불필요)이 주력이고,
네이버 검색 API(NAVER_CLIENT_ID/SECRET 설정 시)는 RSS에 없는 매체를 잡는 보완망이다.

뉴스는 **개별 속보로 즉시 발송하지 않는다.** 예전엔 제목 속보 말머리([속보]/[긴급])만 골라 바로 알렸으나,
그 방식은 "언론사가 속보 딱지를 붙였나"라는 형식 판단일 뿐 트레이딩 가치와 무관해(정치 속보·"코스피 급락"
같은 뻔한 후행 정보) 폐기했다. 지금은 신규 기사를 **헤드라인 버퍼에 쌓기만** 하고,
시황 리포트(`KisPollerService`)가 **지난 1시간치를 읽어 "시장 상황·주도 테마/종목"을 분석하는 재료**로 쓴다.

```
NewsPollerService (별도 스레드 1개에서 세 주기 운용)
  ├─ RssClient.fetch()              언론사·정부 보도자료 RSS 17개 피드 (30초 주기, 기본)
  ├─ RssClient.fetch(구글뉴스)       키워드 검색 RSS — 네이버 사각지대 보완 (120초 주기, 키 불필요)
  └─ NaverNewsClient.search()       키워드별 네이버 뉴스 검색 (240초 주기, 키 설정 시)
  → SeenStore                       이미 본 기사(링크) 스킵, seen_news.txt로 유지
  → (NEWS_EXCLUDE_KEYWORDS 제목 컷)  스포츠·연예 등 잡음 제목은 적재 전 제외
  → NewsHeadlineBuffer.add()        신규 기사를 시간창(2시간) 롤링 버퍼에 적재

KisPollerService.generateMacroReport() (시간당, kis-poller 스레드)
  → NewsHeadlineBuffer.recent(60분)  지난 1시간 헤드라인을 정규화 dedup 후 최대 60건
  → buildFlowFacts + 뉴스 헤드라인    국내 실측 수급 + 뉴스를 한 프롬프트로
  → LlmClient.chatGrounded()         Gemini 그라운딩으로 "시장 상황·우세 종목/섹터" 분석
  → Notifier.send()                  🌐 시황 분석 → 뉴스 채널로 시간당 1건
```

## 패키지 구조

패키지-바이-컨텍스트 + 컨텍스트 내부 경량 헥사고날. 각 컨텍스트는 `domain`(순수 판정·값 객체) /
`application`(폴링·오케스트레이션) / `infra`(외부 API·파일 IO)로 나뉜다. 아키텍처 규칙은
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)에 있고 `ArchUnit` 테스트로 강제된다.

```
com.example.dart
├── App                     조립 루트 — 의존성 생성·연결, 생명주기 (유일하게 전 컨텍스트를 안다)
├── config/AppConfig        환경변수(.env) 로드·검증 — 컨텍스트별 중첩 record로 분해
├── common                  공유 커널 (어떤 컨텍스트도 import하지 않음)
│   ├── domain/  TradingSession(세션 판정), KoreanMoney(금액 표기)
│   ├── text/    TextTable(전각 표시폭 정렬)
│   └── infra/   SeenStore, TrustStores, StockQuoteClient,
│                PollWorker(스레드풀·graceful shutdown), PollBackoff(지수 백오프),
│                RetryScheduler(enrichment 재시도 골격), MarketCalendar(거래일)
├── disclosure              공시 컨텍스트 — DART + KIND 두 인바운드 소스 통합
│   ├── domain/       Disclosure, ContractInfo(+비율 판정), DisclosureKeys(교차 중복 키),
│   │                 NewsFilter(2단계 호재 필터), AlertMessages(순수 문자열 조립)
│   ├── application/  PollerService(DART 폴링), KindPollerService(KIND 폴링),
│   │                 DisclosureEnricher(규모 보강 오케스트레이션·자동매매 신호), DocumentService,
│   │                 KindAlertComposer
│   └── infra/        DartClient, KindClient, KindDocumentClient, KindDisclosure,
│                     DocumentParser, DartException, DocumentNotReadyException
├── pricetrack             공시 후 주가 추적 컨텍스트
│   ├── domain/       Stats(+통계 산출·패턴 분류)
│   └── application/  DisclosurePriceTracker
├── kis                    KIS 시장분석 컨텍스트 (급등·수급·섹터·시황 리포트)
│   ├── domain/       Session, FlowPhase, Investor, KisMoney,
│   │                 VolumeRankItem·TradingValueItem·InvestorFlowItem 등 DTO
│   ├── application/  KisPollerService(스케줄러 파사드), GainerScout, SectorSummaryService,
│   │                 TurnoverRankingService, InvestorFlowService, MacroReportService,
│   │                 KisAlertComposer
│   └── infra/        KisClient, UsFuturesClient, DomesticMarketClient, SectorCacheStore
├── news                  뉴스 수집 컨텍스트 (평면 — 시황 리포트 재료 버퍼링)
│   ├── RssClient, RssFeed, GoogleNewsFeeds, NaverNewsClient, NewsArticle,
│   └── NewsHeadlineBuffer, NewsPollerService
├── trade                 공시 기반 자동매매 컨텍스트 (Stage 1: 드라이런)
│   ├── TradeSignalListener(포트), Position(+주식수·손익 판정), AutoTradeService
├── notify                알림 발송 (평면 — 포트/어댑터)
│   ├── Notifier, HttpNotifier, DiscordService, WebexService
└── llm                   시황 분석 LLM (평면 — 포트/어댑터)
    ├── LlmClient, GeminiClient, OllamaClient
```

## 필터 정책

- **Stage 1 (제목)**: DART `report_nm`은 거래소 표준 서식명이므로 서식명 단위로 룰을 구성한다.
  카테고리: 수주공급계약 / 주주환원 / 투자 / 기술계약 / 특허 / 바이오승인 / 장래계획 / 영업재개 / 소송승소,
  그리고 코스닥 중대 호재 통로인 `투자판단관련주요경영사항`을 악재 키워드만 빼고 전부 받는 **안전망 룰**.
- **전역 제외**: 정정(중복 방지)·해지·철회·취소·중단·취하·반려.
- **Stage 2 (본문)**: 수주공급계약만 — "조건부 계약여부: 해당" 명시 또는 매출액 대비 5% 미만이면 제외.
  본문 조회 실패 시 제목 기준으로 알림(놓치지 않음).
- 키워드 추가/제외는 코드 수정 없이 `FILTER_EXTRA_KEYWORDS` / `FILTER_EXCLUDE_KEYWORDS`로 조정.

## 실행

```bash
cp .env.example .env   # DART_API_KEY 등 입력
./gradlew run          # Windows: .\gradlew.bat run
./gradlew test         # 필터 룰 테스트
```

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DART_API_KEY` | (필수) | DART OpenAPI 인증키 |
| `NOTIFIER` | `webex` | `discord` 또는 `webex` |
| `DISCORD_BOT_TOKEN` / `DISCORD_CHANNEL_ID` | — | NOTIFIER=discord일 때 필수 |
| `WEBEX_BOT_TOKEN` / `WEBEX_ROOM_ID` | — | NOTIFIER=webex일 때 필수 |
| `DISCORD_NEWS_CHANNEL_ID` / `WEBEX_NEWS_ROOM_ID` | — | 설정 시 뉴스 알림을 별도 채널/룸으로 분리 |
| `POLL_INTERVAL_SEC` | `7` | 폴링 주기(초) |
| `CORP_CLS` | `Y,K` | 시장 (Y=코스피, K=코스닥, 콤마 구분, 빈 값=전체) |
| `PBLNTF_TY` | `B,I` | 공시유형 (B=주요사항보고, I=거래소공시) |
| `FILTER_EXTRA_KEYWORDS` | — | 호재 키워드 추가 (콤마 구분, "사용자추가" 카테고리) |
| `FILTER_EXCLUDE_KEYWORDS` | — | 전역 제외 키워드 추가 (콤마 구분) |
| `KIND_ENABLED` | `true` | KIND 선행 감시 켜기/끄기 |
| `KIND_POLL_INTERVAL_SEC` | `15` | KIND 폴링 주기(초) — 07:00~20:00 KST만 동작 |
| `NEWS_ENABLED` | `true` | 뉴스 감시 켜기/끄기 (RSS는 키 없이 동작) |
| `NEWS_RSS_FEEDS` | 검증된 17개 피드 | 언론사·정부 보도자료 RSS (`이름\|URL` 콤마 구분) |
| `NEWS_RSS_POLL_INTERVAL_SEC` | `30` | RSS 폴링 주기(초) — 무료·무제한 |
| `NEWS_GOOGLE_KEYWORDS` | 수주, 품목허가 등 10개 | 구글뉴스 검색 RSS 키워드 (빈 값=비활성화) |
| `NEWS_GOOGLE_POLL_INTERVAL_SEC` | `120` | 구글뉴스 폴링 주기(초) — 비공식 한도, 429 시 상향 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | — | 설정 시 네이버 검색 보완망 활성화 ([developers.naver.com](https://developers.naver.com)에서 발급) |
| `NEWS_POLL_INTERVAL_SEC` | `240` | 네이버 폴링 주기(초) — 일 호출 한도 25,000회 주의 |
| `NEWS_BREAKING_KEYWORDS` | 속보, 긴급, 특보, 플래시, 브레이킹 | **속보 게이트** — 제목 말머리가 이 중 하나면 알림 (콤마 구분). `[N보]` 차수는 자동 인식 |
| `NEWS_KEYWORDS` | 수주, 임상, FDA 등 35개 | 네이버/구글 검색어 (콤마 구분) — 알림 게이트 아님 |
| `NEWS_BAD_KEYWORDS` | 하한가, 소송, 적자 등 28개 | 네이버 검색어 (콤마 구분) — 알림 게이트 아님 |
| `NEWS_MACRO_KEYWORDS` | FOMC, 서킷브레이커 등 6개 | 네이버 검색어 (콤마 구분) — 알림 게이트 아님 |
| `NEWS_EXCLUDE_KEYWORDS` | — | 제목에 포함 시 제외할 키워드 (콤마 구분) |
| `NEWS_MAX_AGE_MIN` | `30` | 이보다 오래된 기사 무시 (첫 실행 시 과거 기사 폭주 방지) |
