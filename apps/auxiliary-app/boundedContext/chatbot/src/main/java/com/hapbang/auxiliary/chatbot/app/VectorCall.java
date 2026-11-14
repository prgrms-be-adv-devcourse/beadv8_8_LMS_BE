package com.hapbang.auxiliary.chatbot.app;

import java.util.function.Supplier;

/**
 * 추천의 벡터 호출(ES 검색·임베딩)만 감싼다. 실패를 {@link Failure}로 바꿔 DB 오류·코드 오류와 구분한다.
 * 추천은 {@link Failure}만 "검색 장애"로 보고 인기순으로 답한다. 그 밖의 예외는 그대로 올라간다.
 */
final class VectorCall {

    private VectorCall() {
    }

    static <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new Failure(e);
        }
    }

    /** 벡터 호출이 실패했다. 원인은 {@link #getCause()} */
    static final class Failure extends RuntimeException {

        Failure(RuntimeException cause) {
            super(cause);
        }
    }
}
