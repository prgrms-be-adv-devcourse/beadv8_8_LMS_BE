package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;

/**
 * 이벤트 하나가 예약에 하는 일. 이벤트를 바꾸는 쪽({@link ScheduleInputs})이 필수 값을 확인해 만들고, {@link ScheduleService}가
 * 적용한다. 예약은 (종류, 대상 ID, 받는 사람)으로 한 건이다(ND-025·ND-034).
 */
public sealed interface ScheduleCommand {

    ScheduleKind kind();

    long targetId();

    long receiverUserId();

    /**
     * 예약을 만든다. 같은 예약이 이미 있으면(취소가 먼저 왔거나 재전달) 아무것도 하지 않는다.
     *
     * @param payload    문구 값(형식 버전 {@code v}, 제목, 기한, ND-140)
     * @param occurredAt 예약을 만든 사건의 시각. 알림 시작 시각보다 이르면 예약을 만들지 않는다(ND-145)
     */
    record Create(ScheduleKind kind, long targetId, long receiverUserId, ScheduleTimes times,
            Map<String, Object> payload, Instant occurredAt) implements ScheduleCommand {
    }

    /**
     * 예약을 취소한다. 예약이 아직 없으면 "취소" 행을 먼저 남긴다(ND-034).
     *
     * @param businessDeadline 그 행의 업무 기한(정리 기준, ND-142). 구매 취소 마감 임박은 대체 기한(ND-143)
     */
    record Cancel(ScheduleKind kind, long targetId, long receiverUserId, Instant businessDeadline)
            implements ScheduleCommand {
    }
}
