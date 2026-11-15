package com.hapbang.auxiliary.chatbot.domain;

/**
 * 사본 반영을 누가 했는지 (#835). Kafka 이벤트와 매시 싱크가 같은 적용기를 부르는데, 싱크만 회차를 남긴다(원본 없음 표시용).
 * 예전에는 회차 ID가 null이면 이벤트라는 규칙이 숨어 있었다.
 */
public final class ApplySource {

    /** Kafka 이벤트 반영. 회차가 없다 */
    public static final ApplySource EVENT = new ApplySource(null);

    private final Long runId;

    private ApplySource(Long runId) {
        this.runId = runId;
    }

    /** 싱크 회차 {@code runId}의 반영 */
    public static ApplySource syncRun(long runId) {
        return new ApplySource(runId);
    }

    /** 싱크 회차 ID. 이벤트면 null(SQL 파라미터 {@code :runId}에 그대로 넣는다) */
    public Long runId() {
        return runId;
    }

    @Override
    public String toString() {
        return runId == null ? "EVENT" : "SYNC_RUN(" + runId + ")";
    }
}
