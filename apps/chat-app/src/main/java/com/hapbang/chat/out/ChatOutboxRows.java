package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.UUID;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatDeliveryType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * 전달·제어 outbox native 조회 결과의 interface projection. 엔티티로 읽지 않아 영속성 컨텍스트에 남은 엔티티 상태가 아니라
 * 문장이 돌려준 값을 그대로 쓴다. 컬럼 별칭(snake_case)을 속성 이름으로 읽고, 문자열 상태는 enum으로 바꿔 받는다.
 */
public final class ChatOutboxRows {

    private ChatOutboxRows() {
    }

    /** 전달 outbox 행의 적재 작업 컬럼 */
    public interface DeliveryJobRow {

        long getId();

        UUID getJobId();

        ChatDeliveryType getDeliveryType();

        long getMessageId();

        long getChatRoomId();

        long getRoomSequence();

        long getRecipientSetId();

        ChatNotifyRule getNotifyRule();

        int getSchemaVersion();
    }

    /** 제어 outbox 행의 적재 작업 컬럼 */
    public interface ControlJobRow {

        long getId();

        UUID getJobId();

        ChatControlType getControlType();

        long getUserId();

        Long getMemberVersion();

        ChatControlReason getReason();

        int getSchemaVersion();
    }

    /** 선점 직후의 적재 시도 횟수 */
    public interface Claimed {

        int getAttemptCount();
    }

    /** 재구성 대상의 체크포인트·재적재 상태 */
    public interface Candidate {

        long getCheckpointId();

        int getGeneration();

        String getStreamEntryId();

        int getRequeueCount();
    }

    public interface ClaimedDeliveryRow extends DeliveryJobRow, Claimed {
    }

    public interface ClaimedControlRow extends ControlJobRow, Claimed {
    }

    public interface DeliveryCandidateRow extends DeliveryJobRow, Candidate {
    }

    public interface ControlCandidateRow extends ControlJobRow, Candidate {
    }

    /** 격리한 작업(관리자 조회). outbox 행이 없는 격리면 outbox 컬럼은 null이다 */
    public interface IsolatedJobRow {

        long getCheckpointId();

        UUID getJobId();

        ChatPublishStatus getPublishStatus();

        int getGeneration();

        int getPublishAttempts();

        int getTotalPublishAttempts();

        String getDeadReason();

        String getDeadStreamEntryId();

        String getPublishError();

        LocalDateTime getDeadAt();

        LocalDateTime getCheckpointUpdatedAt();

        ChatOutboxStatus getOutboxStatus();

        Integer getEnqueueAttempts();

        Integer getTotalEnqueueAttempts();

        String getEnqueueError();

        LocalDateTime getOutboxUpdatedAt();
    }
}
