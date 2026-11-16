package com.hapbang.auxiliary.chatbot.app;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주기 점검의 연속 실패 수(이 pod가 떠 있는 동안). 성공하면 0으로 돌리고, 정한 횟수 이상 연속 실패하면 알릴 차례라고 답한다.
 * 로그는 부르는 쪽이 남긴다(점검마다 문구·alert 표식이 다르다). 여러 스레드가 함께 셀 수 있다.
 */
public final class ConsecutiveFailures {

    private final AtomicInteger count = new AtomicInteger();

    /** 성공했다. 연속 실패 수를 0으로 돌린다 */
    public void succeeded() {
        count.set(0);
    }

    /**
     * 실패를 하나 더 센다.
     *
     * @param alertAfter 이 횟수 이상 연속 실패하면 알린다
     */
    public Failure failed(int alertAfter) {
        int failures = count.incrementAndGet();
        return new Failure(failures, failures >= alertAfter);
    }

    /**
     * @param count 이번 실패까지의 연속 실패 수
     * @param alert 알릴 차례인지(ERROR, 아니면 다시 시도할 실패로 WARN)
     */
    public record Failure(int count, boolean alert) {
    }
}
