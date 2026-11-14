package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;

/**
 * 예약 기록({@code notification_schedule}, ND-025). 모든 쓰기는 호출한 트랜잭션 안에서 한다. 생성·취소는 유일 키 (종류, 대상 ID, 받는
 * 사람)의 충돌 처리로 한 문장 안에서 끝나, 같은 예약의 생성·취소가 어떤 순서·동시성으로 와도 결과가 같다(ND-034).
 */
public interface ScheduleStore {

    /** 예약 상태 */
    enum Status {
        SCHEDULED, SENT, CANCELED, SKIPPED
    }

    /**
     * 예약 행을 넣는다. 같은 예약이 이미 있으면(어떤 상태든) 아무것도 하지 않는다.
     *
     * @param status {@link Status#SCHEDULED} 또는 업무 기한이 지난 생성의 {@link Status#SKIPPED}(ND-136)
     * @return 넣었으면 true
     */
    boolean insertIfAbsent(ScheduleCommand.Create command, Status status, Instant now);

    /**
     * 예약을 취소한다. 행이 없으면 "취소" 행을 넣고(ND-034), "예약" 행이면 취소로 바꾸고, 끝 상태 행은 그대로 둔다.
     *
     * @return 넣었거나 바꿨으면 true
     */
    boolean cancel(ScheduleCommand.Cancel command, Instant now);

    /**
     * 보낼 시각이 지났고 업무 기한은 아직 지나지 않은 "예약" 행 ID를 보낼 시각 순으로 최대 {@code limit}개. 기한이 지난 예약이 앞자리를
     * 차지해 유효한 예약이 밀리지 않게 한다
     */
    List<Long> findDueIds(Instant now, int limit);

    /**
     * 업무 기한이 지난(ND-144) "예약" 행을 최대 {@code limit}개 "건너뜀"으로 끝낸다(ND-135). 다른 트랜잭션이 쥔 행은 건너뛴다
     *
     * @return 끝낸 행 수
     */
    int skipExpired(Instant now, int limit);

    /** 행을 잠그고 읽는다(보내기 직전 재확인, ND-029). 없으면(정리됨) 빈 값 */
    Optional<Due> lockById(long id);

    /** "예약" 행을 끝 상태로 바꾼다 */
    void finish(long id, Status status, Instant now);

    /**
     * "예약" 행의 실패 횟수를 1 늘린다(ND-147). 호출한 쪽이 실패한 저장 트랜잭션과 다른 트랜잭션에서 부른다
     *
     * @return 늘린 뒤의 실패 횟수. 행이 없거나 이미 끝났으면 빈 값
     */
    java.util.OptionalInt recordFailure(long id, Instant now);

    /**
     * 끝 상태(보냄·취소·건너뜀)이고 업무 기한 + {@code retentionDays}일이 지난 행을 최대 {@code limit}개 지운다(ND-142). "예약" 행은
     * 지우지 않는다
     *
     * @return 지운 행 수
     */
    int deleteFinished(Instant now, int retentionDays, int limit);

    /**
     * 보내기 직전에 잠그고 읽은 예약
     *
     * @param payload 문구 값(JSON 객체)
     */
    record Due(long id, ScheduleKind kind, long targetId, long receiverUserId, Status status, Instant sendAt,
            Instant businessDeadline, java.util.Map<String, Object> payload) {
    }
}
