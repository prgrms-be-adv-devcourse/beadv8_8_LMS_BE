package com.hapbang.shared.internal;

/** 모듈 간 내부 HTTP API(`/api/v1/internal/**`) 헤더. 호출하는 Client와 받는 쪽 인증 필터가 같은 이름을 쓴다 */
public final class InternalApiHeaders {

    /** 내부 API 키. 값은 {@code hapbang.internal-api.key}(환경 변수 {@code HAPBANG_INTERNAL_API_KEY})다 */
    public static final String API_KEY = "X-Internal-Api-Key";

    private InternalApiHeaders() {
    }
}
