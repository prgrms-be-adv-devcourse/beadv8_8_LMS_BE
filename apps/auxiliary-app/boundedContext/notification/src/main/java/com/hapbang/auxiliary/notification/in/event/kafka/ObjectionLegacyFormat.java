package com.hapbang.auxiliary.notification.in.event.kafka;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이의 제기 결과 이벤트가 #818(강의 D83) 이전 형식인지 원본 JSON으로 판별한다(ND-086).
 * <ul>
 *     <li>#818이 더한 필드의 키가 원본에 모두 없으면 {@link #LEGACY}다. #818 이전 코드가 직접 발행한 메시지다. 소비자는 알림 없이
 *     커밋한다.</li>
 *     <li>키가 모두 있고 값도 모두 있으면 {@link #CURRENT}다.</li>
 *     <li>그 밖({@code null} 값, 일부 키만 없음, JSON 객체가 아닌 본문)은 {@link #UNDETERMINED}다. #818 이전 발행 기록의 재전송분은 현재 레코드로 다시
 *     직렬화돼 키가 생기고 값이 {@code null}이라 신규 이벤트의 값 누락과 구별할 수 없다. 소비자는 건너뛰지 않고 기존 오류 정책
 *     (검증 실패, 재시도 없이 DLT)을 따른다.</li>
 * </ul>
 * {@code eventVersion} 헤더는 #818 전후 모두 {@code 1}이라 판별 근거가 되지 않는다. 강의 알림 리스너가
 * 역직렬화 전에 부른다(ND-096).
 */
public enum ObjectionLegacyFormat {
    /** #818 이전 형식(추가 필드 키가 모두 없음) */
    LEGACY,
    /** 이전 형식인지 알 수 없음(값이 {@code null}이거나 일부 키만 없음, JSON 객체가 아님) */
    UNDETERMINED,
    /** 지금 형식(추가 필드가 모두 값과 함께 있음) */
    CURRENT;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final List<String> RECEIVERS_AND_TITLE = List.of("buyerUserId", "sellerUserId", "lectureTitle");

    /** 이벤트별 #818 추가 필드(event-contracts.md 2장) */
    private static final Map<Class<?>, List<String>> ADDED_FIELDS = Map.of(
            LectureObjectionResolvedEvent.class, with("resolvedAt"),
            LectureObjectionReturnCompletedEvent.class, with("returnedAt"),
            LectureObjectionWithdrawnEvent.class, with("withdrawnAt"),
            LectureObjectionReturnActionRequiredEvent.class, with("returnDelaySequence", "occurredAt"));

    /**
     * @param eventType 이의 제기 결과 이벤트 4종 중 하나
     * @param json      Kafka 레코드 값(원본 JSON)
     * @throws IllegalArgumentException 이의 제기 결과 이벤트가 아니면
     */
    public static ObjectionLegacyFormat classify(Class<?> eventType, byte[] json) {
        List<String> fields = ADDED_FIELDS.get(eventType);
        if (fields == null) {
            throw new IllegalArgumentException("이의 제기 결과 이벤트가 아닙니다: " + eventType);
        }
        JsonNode node = JSON.readTree(json);
        if (node == null || !node.isObject()) {
            // 빈 본문·null·배열·문자열은 키가 없는 것이 아니라 읽을 수 없는 본문이다
            return UNDETERMINED;
        }
        long present = fields.stream().filter(node::has).count();
        if (present == 0) {
            return LEGACY;
        }
        boolean complete = fields.stream().allMatch(field -> node.has(field) && !node.get(field).isNull());
        return complete ? CURRENT : UNDETERMINED;
    }

    private static List<String> with(String... eventFields) {
        return Stream.concat(RECEIVERS_AND_TITLE.stream(), Arrays.stream(eventFields)).toList();
    }
}
