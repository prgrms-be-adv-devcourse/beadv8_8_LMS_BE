package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * FAQ 대화(#715)·자유 질문(#720) 스트림이 함께 쓰는 뼈대. 허가({@link LlmCallGuard.Permit})를 받은 뒤의 흐름이다.
 * <ul>
 *     <li>전체 시간 상한: 첫 응답은 {@code total}, 다음 응답은 남은 시간까지 기다린다. 넘으면 {@link java.util.concurrent.TimeoutException}.</li>
 *     <li>빈 글 조각은 보내지 않는다. 조각을 하나라도 보냈는지 기억해 끝·실패 처리에 넘긴다.</li>
 *     <li>모델이 끝까지 답하면 허가를 성공으로 돌려준 뒤 {@link Completed}의 이벤트를 붙인다.</li>
 *     <li>실패하면 {@link Failed}가 허가를 돌려주고 이어 보낼 이벤트를 정한다(첫 조각 전이면 fallback 답, 뒤면 {@code error}).</li>
 *     <li>사용자가 연결을 끊으면 성공도 실패도 아니다. 자리만 돌려준다.</li>
 * </ul>
 * 이벤트 모양·로그·지표는 종류마다 다르므로 부르는 쪽이 정한다.
 */
final class LlmTokenStream {

    private LlmTokenStream() {
    }

    /**
     * @param call      받은 허가(막히지 않음)
     * @param total     전체 시간 상한
     * @param responses 모델 응답 스트림
     * @param texts     시간 상한을 건 응답 → 글 조각
     * @param token     글 조각 → 이벤트
     */
    static <R, E> Flux<E> stream(LlmCallGuard.Permit call, Duration total, Flux<R> responses,
            Function<Flux<R>, Flux<String>> texts, Function<String, E> token, Completed<E> completed, Failed<E> failed) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        long deadline = System.nanoTime() + total.toNanos();
        Flux<R> limited = responses
                .timeout(Mono.delay(total),
                        response -> Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
        Flux<E> tokens = texts.apply(limited)
                .filter(text -> !text.isEmpty())
                .map(text -> {
                    emitted.set(true);
                    return token.apply(text);
                });
        return tokens
                .concatWith(Flux.defer(() -> {
                    call.succeeded();
                    return completed.events(emitted.get());
                }))
                .onErrorResume(e -> failed.events(e, emitted.get()))
                .doOnCancel(call::cancelled);
    }

    /** 모델이 끝까지 답한 뒤 붙일 이벤트 */
    @FunctionalInterface
    interface Completed<E> {

        /** @param emitted 글 조각을 하나라도 보냈는지 */
        Flux<E> events(boolean emitted);
    }

    /** 실패했을 때 허가를 돌려주고 이어 보낼 이벤트 */
    @FunctionalInterface
    interface Failed<E> {

        /** @param emitted 실패 전에 글 조각을 하나라도 보냈는지 */
        Flux<E> events(Throwable error, boolean emitted);
    }
}
