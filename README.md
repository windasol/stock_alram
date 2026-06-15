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

뉴스 폴러는 공시 폴러와 **독립된 스레드에서 병렬로** 동작한다 (Notifier만 공유).
속도 우선으로 소스를 계층화했다: **언론사 RSS 속보 직접 폴링**(포털 색인 지연 없음, 키 불필요)이 주력이고,
네이버 검색 API(NAVER_CLIENT_ID/SECRET 설정 시)는 RSS에 없는 매체를 잡는 보완망이다.

```
NewsPollerService (별도 스레드 1개에서 세 주기 운용)
  ├─ RssClient.fetch()              언론사·정부 보도자료 RSS 17개 피드 (30초 주기, 기본)
  ├─ RssClient.fetch(구글뉴스)       키워드 검색 RSS — 네이버 사각지대 보완 (120초 주기, 키 불필요)
  └─ NaverNewsClient.search()       키워드별 네이버 뉴스 검색 (240초 주기, 키 설정 시)
  → SeenStore                       이미 알린 기사(링크) 스킵, seen_news.txt로 유지
  → NewsKeywordClassifier           호재/악재/시황 분류 — RSS 전체 기사의 1차 필터 겸 태그
  → NewsArticleFilter               제외 키워드 / 오래된 기사(기본 30분) / 유사 제목 중복(60분 창) / 시황 쿨다운(10분)
  → NewsAlertComposer.compose()     [호재 뉴스 · 키워드] 형식 마크다운 조립
  → Notifier.send()                 공시와 같은 채널로 전송
```

**뉴스 분류 규칙** (NewsKeywordClassifier):
- 악재 → 호재 → 시황 순으로 검사. 둘 다 걸리면 악재 우선.
- **반전어**: 호재/악재 키워드와 반전어(철회·취소·실패·해제 등)가 같이 있으면 분류를 뒤집는다.
  "유상증자 철회"=호재, "수주 취소"=악재, "거래정지 해제"=호재, "임상 3상 실패"=악재.
- **시황 2단 구조**: FOMC·서킷브레이커처럼 그 자체로 이벤트인 단독 키워드는 바로 알리고,
  유가·미국·이란·트럼프 같은 주제어는 충격어(급등·공습·제재·관세 등)와 같은 제목에 있을 때만 알린다 —
  주제어 단독 일상 기사("트럼프, 보좌관 임명")는 무시.
- **시황 쿨다운**: 같은 시황 키워드는 10분에 1회만 — 전쟁·유가 이슈의 후속 보도 폭주 방지.
- RSS가 먼저 알린 이슈는 유사 제목 중복 필터가 네이버 쪽 같은 기사를 자동 억제한다.

## 패키지 구조

```
com.example.dart
├── App                  조립 루트 — 의존성 생성·연결, 생명주기
├── config/AppConfig     환경변수(.env) 로드·검증
├── dart/DartClient      DART OpenAPI 클라이언트 (list.json, document.xml)
├── kind                 KIND(거래소 공시) 선행 감시 — DART보다 빠른 공시 소스
│   ├── KindClient           오늘의공시 AJAX 호출·HTML 테이블 파싱
│   ├── KindDisclosure       공시 1행 DTO (시장 코드 변환 포함)
│   ├── KindAlertComposer    헤더 전용 알림 조립 (본문 없이 즉시)
│   └── KindPollerService    폴링 루프 (운영시간 제한·백오프·교차 중복)
├── filter/NewsFilter    2단계 호재 필터 (룰 테이블 + 안전망 룰)
├── news                 뉴스 병렬 감시 (공시 파이프라인과 독립)
│   ├── RssClient            언론사 속보 RSS 클라이언트 (가장 빠른 소스)
│   ├── RssFeed              피드 1개 ("이름|URL" 설정 파싱)
│   ├── GoogleNewsFeeds      구글뉴스 키워드 검색 RSS 피드 팩토리 (키 불필요)
│   ├── NaverNewsClient      네이버 뉴스 검색 Open API 클라이언트 (보완망)
│   ├── NewsArticle          기사 1건 DTO (HTML 제거된 평문)
│   ├── NewsKeywordClassifier 호재/악재/시황 키워드 그룹 분류 (악재 우선)
│   ├── NewsArticleFilter    제외 키워드·나이·유사 제목 중복 필터
│   ├── NewsAlertComposer    뉴스 알림 메시지 조립
│   └── NewsPollerService    RSS·네이버 이원 폴링 루프 (별도 스레드)
├── model/Disclosure     공시 1건 DTO (corp_cls → 코스피/코스닥 변환 포함)
├── parse/DocumentParser 원문 ZIP → 평문 변환, 핵심 정보 요약 추출
├── service
│   ├── PollerService    폴링 루프, 필터 → 알림 오케스트레이션
│   └── DocumentService  원문 조회 (문서 캐시로 중복 다운로드 방지)
├── notify
│   ├── Notifier         전송 인터페이스 (send(String))
│   ├── HttpNotifier     JSON POST 공통 골격 (전송 실패는 삼킴)
│   ├── AlertComposer    알림 메시지 조립 (헤더 + 원문 요약)
│   ├── DiscordService   Discord 채널 메시지 전송 (2,000자 제한)
│   └── WebexService     Webex 룸 메시지 전송 (7,000자 제한)
└── util
    ├── SeenStore        처리한 공시 ID 영속 저장
    └── DisclosureKeys   DART↔KIND 교차 중복 키 (회사명+제목 정규화)
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
| `NEWS_KEYWORDS` | 수주, 임상, FDA 등 35개 | 호재 키워드 (콤마 구분, 재현율 우선으로 넓게) |
| `NEWS_BAD_KEYWORDS` | 하한가, 소송, 적자 등 28개 | 악재 키워드 (콤마 구분) |
| `NEWS_MACRO_KEYWORDS` | FOMC, 서킷브레이커 등 6개 | 단독으로 알리는 시황 키워드 |
| `NEWS_MACRO_TOPICS` | 유가, 미국, 이란, 트럼프 등 14개 | 시황 주제어 — 충격어와 조합 시에만 알림 |
| `NEWS_MACRO_TRIGGERS` | 급등, 공습, 제재, 관세 등 26개 | 시황 충격어 |
| `NEWS_FLIP_KEYWORDS` | 철회, 취소, 승소 등 19개 | 호재↔악재 반전어 (매칭 키워드 자신은 검색 제외) |
| `NEWS_MACRO_COOLDOWN_MIN` | `10` | 같은 시황 키워드 재알림 최소 간격(분) |
| `NEWS_EXCLUDE_KEYWORDS` | — | 제목에 포함 시 제외할 키워드 (콤마 구분) |
| `NEWS_MAX_AGE_MIN` | `30` | 이보다 오래된 기사 무시 (첫 실행 시 과거 기사 폭주 방지) |
