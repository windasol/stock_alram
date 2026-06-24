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

    /** 로깅·표시용 식별자(모델명). */
    String model();
}
