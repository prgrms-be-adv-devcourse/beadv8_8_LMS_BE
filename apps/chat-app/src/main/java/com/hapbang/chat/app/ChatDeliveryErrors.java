package com.hapbang.chat.app;

import org.springframework.core.NestedExceptionUtils;

/**
 * 전달 파이프라인 실패 기록용 오류 설명. Redis·DB 예외는 감싼 예외의 메시지만으로는 원인을 알 수 없으므로
 * (예: {@code RedisSystemException: Error in execution}) 가장 안쪽 원인을 함께 남긴다. 길이는 저장할 때 자른다.
 */
public final class ChatDeliveryErrors {

    private ChatDeliveryErrors() {
    }

    /**
     * 종료 중 인터럽트로 끝난 실패인지(현재 스레드에 인터럽트가 걸렸거나 원인 사슬에 {@link InterruptedException}이 있다).
     * 작업의 실패도 인프라 장애도 아니므로 시도로 세지 않고 멈춤으로 보내지 않는다.
     */
    public static boolean isInterrupted(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        int depth = 0;
        for (Throwable current = error; current != null && depth < 32; current = current.getCause(), depth++) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /** {@code <겉 예외 이름> ← <근본 원인 예외: 메시지>}. 겉과 근본 원인이 같으면 근본 원인만 */
    public static String describe(Throwable error) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
        if (root == error) {
            return error.toString();
        }
        return error.getClass().getSimpleName() + " ← " + root;
    }
}
