# DART 실시간 호재 알림 봇

DART(전자공시시스템) 최신 공시를 폴링해 호재성 공시를 골라 Discord/Webex로 알림을 보내는 봇.

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

## 패키지 구조

```
com.example.dart
├── App                  조립 루트 — 의존성 생성·연결, 생명주기
├── config/AppConfig     환경변수(.env) 로드·검증
├── dart/DartClient      DART OpenAPI 클라이언트 (list.json, document.xml)
├── filter/NewsFilter    2단계 호재 필터 (룰 테이블 + 안전망 룰)
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
└── util/SeenStore       처리한 공시 ID 영속 저장
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
| `POLL_INTERVAL_SEC` | `7` | 폴링 주기(초) |
| `CORP_CLS` | `Y,K` | 시장 (Y=코스피, K=코스닥, 콤마 구분, 빈 값=전체) |
| `PBLNTF_TY` | `B,I` | 공시유형 (B=주요사항보고, I=거래소공시) |
| `FILTER_EXTRA_KEYWORDS` | — | 호재 키워드 추가 (콤마 구분, "사용자추가" 카테고리) |
| `FILTER_EXCLUDE_KEYWORDS` | — | 전역 제외 키워드 추가 (콤마 구분) |
