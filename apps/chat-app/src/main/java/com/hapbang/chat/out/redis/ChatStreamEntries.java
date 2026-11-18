package com.hapbang.chat.out.redis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.hapbang.chat.app.port.ControlJob;
import com.hapbang.chat.app.port.DeliveryJob;
import com.hapbang.chat.app.port.OutboxJob;

/**
 * 작업 큐(Redis Stream) 항목의 필드 규칙. 항목에는 DB 원본을 가리키는 참조만 싣는다
 * (메시지 본문·수신자 목록을 복제하지 않는다, 설계 4.2).
 */
final class ChatStreamEntries {

    static final String JOB_ID = "jobId";
    static final String TYPE = "type";
    static final String SCHEMA_VERSION = "schemaVersion";
    static final String MESSAGE_ID = "messageId";
    static final String ROOM_ID = "roomId";
    static final String ROOM_SEQUENCE = "roomSequence";
    static final String RECIPIENT_SET_ID = "recipientSetId";
    static final String NOTIFY_RULE = "notifyRule";
    static final String USER_ID = "userId";
    static final String MEMBER_VERSION = "memberVersion";
    static final String REASON = "reason";

    private ChatStreamEntries() {
    }

    /** outbox 행을 큐 항목 필드로 바꾼다. 재적재해도 같은 안정 ID를 싣는다 */
    static Map<String, String> fieldsOf(OutboxJob job) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(JOB_ID, job.jobId().toString());
        switch (job) {
            case DeliveryJob delivery -> {
                fields.put(TYPE, delivery.type().name());
                fields.put(SCHEMA_VERSION, Integer.toString(delivery.schemaVersion()));
                fields.put(MESSAGE_ID, Long.toString(delivery.messageId()));
                fields.put(ROOM_ID, Long.toString(delivery.roomId()));
                fields.put(ROOM_SEQUENCE, Long.toString(delivery.roomSequence()));
                fields.put(RECIPIENT_SET_ID, Long.toString(delivery.recipientSetId()));
                fields.put(NOTIFY_RULE, delivery.notifyRule().name());
            }
            case ControlJob control -> {
                fields.put(TYPE, control.type().name());
                fields.put(SCHEMA_VERSION, Integer.toString(control.schemaVersion()));
                fields.put(USER_ID, Long.toString(control.userId()));
                if (control.memberVersion() != null) {
                    fields.put(MEMBER_VERSION, Long.toString(control.memberVersion()));
                }
                fields.put(REASON, control.reason().name());
            }
        }
        return fields;
    }

    /** 항목의 안정 ID. 없거나 형식이 틀리면 빈 값 */
    static Optional<UUID> jobIdOf(Map<String, String> fields) {
        String value = fields == null ? null : fields.get(JOB_ID);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
