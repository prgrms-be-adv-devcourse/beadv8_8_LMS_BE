package com.hapbang.chat.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.app.ChatOutboxStore.ControlJob;
import com.hapbang.chat.app.ChatOutboxStore.DeliveryJob;
import com.hapbang.chat.app.ChatOutboxStore.OutboxJob;

/**
 * 작업 큐 항목의 필드 규칙과 항목 위치 비교. 항목에는 DB 원본을 가리키는 참조만 싣는다
 * (메시지 본문·수신자 목록을 복제하지 않는다, 설계 4.2).
 */
public final class ChatStreamEntries {

    public static final String JOB_ID = "jobId";
    public static final String TYPE = "type";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String MESSAGE_ID = "messageId";
    public static final String ROOM_ID = "roomId";
    public static final String ROOM_SEQUENCE = "roomSequence";
    public static final String RECIPIENT_SET_ID = "recipientSetId";
    public static final String NOTIFY_RULE = "notifyRule";
    public static final String USER_ID = "userId";
    public static final String MEMBER_VERSION = "memberVersion";
    public static final String REASON = "reason";

    /** 회수를 처음부터 시작하는 위치, 그룹을 처음부터 읽는 위치 */
    public static final String START = "0-0";

    private ChatStreamEntries() {
    }

    /** outbox 행을 큐 항목 필드로 바꾼다. 재적재해도 같은 안정 ID를 싣는다 */
    public static Map<String, String> fieldsOf(OutboxJob job) {
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
    public static Optional<UUID> jobIdOf(QueuedJob job) {
        String value = job.fields() == null ? null : job.fields().get(JOB_ID);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 항목 위치({@code <ms>-<seq>}) 비교 */
    public static int compare(String left, String right) {
        long[] a = parse(left);
        long[] b = parse(right);
        int byTime = Long.compare(a[0], b[0]);
        return byTime != 0 ? byTime : Long.compare(a[1], b[1]);
    }

    public static String min(String left, String right) {
        return compare(left, right) <= 0 ? left : right;
    }

    /** 이 시각보다 앞서 적재된 항목을 가르는 위치 */
    public static String positionAt(Instant instant) {
        return instant.toEpochMilli() + "-0";
    }

    private static long[] parse(String entryId) {
        int dash = entryId.indexOf('-');
        if (dash < 0) {
            return new long[] {Long.parseLong(entryId), 0};
        }
        return new long[] {Long.parseLong(entryId.substring(0, dash)), Long.parseLong(entryId.substring(dash + 1))};
    }
}
