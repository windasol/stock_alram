package com.example.dart.disclosure.infra;

/**
 * DART document.xml 원문이 아직 공개되지 않은 상태(status 014)를 나타낸다.
 *
 * 목록(list.json)은 공시 발행 즉시 노출되지만 원문 파일은 수 분~수 시간 뒤에야 다운로드 가능하다.
 * 그 전까지 DART는 {@code <status>014</status>}를 반환하므로, 영구 실패가 아니라
 * "잠시 뒤 다시 조회하면 되는" 일시적 상태로 구분해 호출 측이 지연 재조회할 수 있게 한다.
 */
public class DocumentNotReadyException extends DartException {

    public DocumentNotReadyException(String message) {
        super(message);
    }
}
