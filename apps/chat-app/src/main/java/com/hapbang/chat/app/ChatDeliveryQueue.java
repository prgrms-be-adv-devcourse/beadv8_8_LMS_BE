package com.hapbang.chat.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.app.dto.ReclaimedJobs;

/**
 * 커밋된 전달·제어 작업의 참조를 담는 작업 큐. 적재·그룹 소비·pending 회수·ACK·안전한 정리를 맡는다.
 * 원본은 chat DB이며, 이 큐는 DB에서 다시 만들 수 있는 전달 버퍼다. DB 트랜잭션 안에서 부르지 않는다.
 * 큐에 연결할 수 없으면(연결 거부·시간 초과·끊김) 모든 메서드가 {@link ChatDeliveryUnavailableException}을 던진다.
 * 큐가 메모리 상한으로 쓰기를 거절하면 {@link ChatDeliveryBackpressureException}을 던진다.
 * <ul>
 *     <li>항목 위치({@code entryId})는 전송 위치일 뿐이고, 작업 식별은 항목에 담은 안정 ID로 한다.</li>
 *     <li>ACK는 그룹의 미확인 목록에서만 지운다. 항목 본문은 {@link #trimBefore}로만 정리한다.</li>
 * </ul>
 * 설계: {@code apps/chat-app/docs/final-design.md} 4.2·4.5
 */
public interface ChatDeliveryQueue {

    /**
     * 필수 consumer group을 처음부터(보존된 첫 항목부터) 읽도록 만든다. 큐가 없으면 함께 만든다.
     * 이미 있는 그룹은 그대로 둔다(위치를 바꾸지 않는다).
     */
    void ensureGroup(ChatJobStream stream);

    /**
     * 작업을 적재한다.
     *
     * @return 적재한 항목 위치
     */
    String enqueue(ChatJobStream stream, Map<String, String> fields);

    /**
     * 그룹에 아직 배분되지 않은 새 작업을 읽는다. 읽은 작업은 ACK할 때까지 이 consumer의 미확인 목록에 남는다.
     *
     * @param block 새 작업이 없을 때 기다릴 최대 시간. 0보다 커야 한다
     * @throws ChatQueueGroupMissingException 큐나 그룹이 없을 때 (Redis 유실)
     */
    List<QueuedJob> readNew(ChatJobStream stream, String consumer, int count, Duration block);

    /**
     * {@code minIdle} 넘게 확인되지 않은 미확인 작업을 이 consumer로 가져온다. 반환한 cursor로 이어서 탐색한다.
     * 본문이 사라진 미확인 항목은 {@link ReclaimedJobs#deletedEntryIds()}로 알린다. 성공 처리로 보지 않는다.
     *
     * @param cursor 처음이면 {@code 0-0}
     * @throws ChatQueueGroupMissingException 큐나 그룹이 없을 때 (Redis 유실)
     */
    ReclaimedJobs reclaim(ChatJobStream stream, String consumer, Duration minIdle, String cursor, int count);

    /** 처리를 끝낸 작업을 그룹의 미확인 목록에서 지운다. 본문은 남는다 */
    void ack(ChatJobStream stream, String entryId);

    /**
     * 항목이 아직 처리될 수 있는지. 본문이 남아 있고, 그룹에 아직 배분되지 않았거나 확인(ACK)되지 않은 미확인 목록에 있으면 true다.
     * 큐·그룹·본문이 없거나 이미 확인된 항목이면 false다.
     */
    boolean isOutstanding(ChatJobStream stream, String entryId);

    /**
     * 큐에 연결돼 있고 쓰기 명령을 받는지 확인한다(TTL이 있는 작은 쓰기). PING만으로는 READONLY 복제본·LOADING·MASTERDOWN처럼
     * 응답은 하지만 적재·읽기를 거절하는 상태를 가려내지 못한다.
     *
     * @throws ChatDeliveryUnavailableException  연결할 수 없거나 지금 명령을 받을 수 없을 때(READONLY 등)
     * @throws ChatDeliveryBackpressureException 연결돼 있지만 메모리 상한·영속화 오류로 쓰기를 거절할 때
     */
    void ping();

    /** 이 consumer가 더 읽지 않을 때 그 consumer에 묶어 둔 연결 등 자원을 놓는다. 미확인 목록은 그대로 남는다 */
    void releaseConsumer(String consumer);

    /** 필수 그룹의 배분·미확인 위치. 큐나 그룹이 없으면 빈 값 */
    Optional<QueueGroupState> groupState(ChatJobStream stream);

    /**
     * {@code minEntryId}보다 앞선 항목 본문을 지운다. 호출한 쪽이 모든 필수 그룹의 미배분·미확인 위치와 보존 기간으로
     * 안전 경계를 계산해 넘긴다. 길이만 보고 자르지 않는다.
     *
     * @return 지운 항목 수
     */
    long trimBefore(ChatJobStream stream, String minEntryId);
}
