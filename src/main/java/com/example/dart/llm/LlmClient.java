package com.example.dart.llm;

/**
 * LLM 요약 생성 추상화 — 로컬(Ollama)·클라우드(Gemini) 구현을 설정으로 교체한다.
 * 호출부(장 흐름 리포트)는 이 인터페이스만 알면 되고, 어떤 모델인지는 신경 쓰지 않는다.
 */
public interface LlmClient {

    /**
     * 시스템·사용자 프롬프트로 한 번 요약을 받는다.
     * @return 응답 텍스트. 실패(네트워크·키·모델·타임아웃 등) 시 null — 호출부가 리포트를 건너뛴다.
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 실시간 웹 검색(그라운딩)을 켜고 요약을 받는다 — 모델 지식 컷오프 밖의 "지금 왜?"(미선물 등락 이유,
     * 경제 일정, 시황 원인)를 인용과 함께 답하게 한다. 그라운딩을 지원하지 않는 구현은 평문 {@link #chat}으로 폴백한다.
     * @return 응답 텍스트(가능하면 출처 포함). 실패 시 null.
     */
    default String chatGrounded(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt);   // 기본: 그라운딩 미지원 → 평문
    }

    /** 로깅·표시용 식별자(모델명). */
    String model();
}
