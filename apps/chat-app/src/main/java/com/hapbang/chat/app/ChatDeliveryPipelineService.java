package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatJobStream;

/**
 * 전달 파이프라인 실행기({@code in/stream})가 부르는 작업 한 번씩의 진입점. 실행기는 스레드·반복·멈춤만 맡고, 처리는 이 계약 뒤의
 * app 조정 서비스(relay·소비·회수·재구성·정리·전달 가용성)가 한다.
 */
public interface ChatDeliveryPipelineService {

    /** 새 실행을 시작하기 전에 전달 가용성 상태(Redis 멈춤·자기 구독 단절·메모리 상한 재확인)를 처음으로 돌린다 */
    void resetAvailability();

    /** 그 큐의 consumer group이 없으면 만든다 */
    void ensureGroup(ChatJobStream stream);

    /**
     * outbox의 적재 대상을 한 묶음 큐에 적재한다.
     *
     * @return 처리한 수
     */
    int relayBatch(ChatJobStream stream);

    /**
     * 그 consumer로 새 작업을 한 번 읽어 처리한다. 새 작업이 없으면 블로킹 읽기 시간만큼 기다린다.
     *
     * @return 처리한 수
     */
    int pollNew(ChatJobStream stream, String consumer);

    /**
     * 오래 확인되지 않은 작업을 {@code budget}만큼 가져와 다시 처리한다.
     *
     * @return 처리한 수
     */
    int reclaim(ChatJobStream stream, String consumer, int budget);

    /**
     * 재구성 주기가 됐거나 요청이 있으면 큐 유실을 DB 미완료로 대조해 다시 적재한다.
     *
     * @return 다시 적재한 수
     */
    int reconstructIfDue(ChatJobStream stream);

    /**
     * 안전한 경계 앞의 처리 끝난 항목을 큐에서 지운다.
     *
     * @return 지운 수
     */
    long trim(ChatJobStream stream);

    /** 끝난 worker의 consumer 자원을 놓는다 */
    void releaseConsumer(String consumer);
}
