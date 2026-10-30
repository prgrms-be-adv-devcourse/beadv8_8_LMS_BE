package com.hapbang.chat.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 메시지에 담는 값({@code chat_message.notification_data})의 형식과 읽고 쓰기. 서버는 문구를 만들지 않고 알림 종류별 사실만
 * 남기며, 프론트가 종류별 템플릿으로 문구를 만든다. 계약은 {@code apps/chat-app/docs/contracts/notification-data.md}다.
 * <ul>
 *     <li>값은 알림 종류마다 record 하나로 둔다: {@link AppearanceRequestSentData}, {@link AppearanceApplicationSubmittedData}.
 *     공통 상위 타입을 두지 않고, 응답에도 종류마다 필드를 따로 둔다. 읽을 때는 알림 종류로 record를 고른다.</li>
 *     <li>모든 종류에 형식 버전 {@code v}를 담는다. 키를 빼거나 뜻·형식을 바꾸면 버전을 올린다.</li>
 *     <li>일시는 KST 기준의 오프셋 없는 {@code yyyy-MM-dd'T'HH:mm:ss} 문자열이다(API의 다른 일시 필드와 같은 형식). 금액은 숫자(HP)다.
 *     일시를 문자열로 두어 직렬화 설정(Hibernate·Spring·Redis)과 관계없이 같은 형식이 된다.</li>
 * </ul>
 */
@Slf4j
public final class NotificationData {

    /** 현재 형식 버전 */
    public static final int VERSION = 1;

    private static final DateTimeFormatter KST_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    /**
     * 값 record를 쓰고 읽는 JSON 변환기. 쓰기는 기본 설정이고, 아래 설정은 읽기에만 작용한다.
     * <ul>
     *     <li>같은 버전에서 더한 키는 무시하고 읽는다.</li>
     *     <li>타입이 다른 값을 바꿔 읽지 않는다(문자열 금액을 숫자로, 숫자 닉네임을 문자열로, 소수 버전을 정수로 바꾸지 않는다).
     *     잘못된 값이 v1 값처럼 나가면 프론트가 대체 표시를 쓸 수 없기 때문이다.</li>
     *     <li>키가 없거나 null이면 실패로 본다(기본 타입을 0으로 채우지 않는다).</li>
     * </ul>
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private NotificationData() {
    }

    /** 알림 종류별 record를 저장할 JSON으로 바꾼다 */
    static String write(Object data) {
        return JSON.writeValueAsString(data);
    }

    /**
     * 저장한 JSON을 알림 종류의 record로 읽는다. 값이 없으면(일반·시스템 메시지, 문구로 남긴 기존 알림) null이다.
     * 읽을 수 없는 값도 이력 조회를 막지 않도록 로그만 남기고 null로 둔다(프론트는 {@code content}를 보여 준다).
     *
     * @param type 알림 종류의 record. 호출하는 쪽({@link ChatMessage})이 알림 종류로 고른다
     */
    static <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException e) {
            log.warn("알림 값을 읽을 수 없어 비워 둠 type={}, error={}", type.getSimpleName(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** 출연 모듈이 넘긴 KST 일시를 그대로 적는다 (시간대 변환 없음, 초 아래는 버린다) */
    static String kst(LocalDateTime dateTime) {
        return KST_DATE_TIME.format(dateTime);
    }
}
