package com.hapbang.chat.app;

import java.util.List;

import org.springframework.util.Assert;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;
import com.hapbang.chat.domain.ChatGroupInviteRequest;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.GroupHostUnavailableData;
import com.hapbang.chat.domain.GroupMemberKickedData;
import com.hapbang.chat.domain.GroupMemberLeftData;
import com.hapbang.chat.domain.GroupMembersInvitedData;
import com.hapbang.chat.domain.GroupRoomClosedData;
import com.hapbang.chat.domain.NotificationKind;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅이 DB에 JSON으로 저장하는 값의 쓰기와 읽기. 도메인은 JSON 문자열만 담고 JSON 변환은 여기서 한다.
 * <ul>
 *     <li>알림 값({@code chat_message.notification_data}): 알림 종류마다 record 하나다. 형식은
 *     {@link com.hapbang.chat.domain.NotificationData}와 {@code apps/chat-app/docs/contracts/notification-data.md}를 따른다.
 *     쓸 때는 record 구성 요소 순서의 키로 쓰고, 읽을 때는 알림 종류로 record를 고른다.</li>
 *     <li>단체방 초대 결과({@code chat_group_invite_request.results}): 회원별 결과 목록이다.</li>
 * </ul>
 * 저장한 값은 이미 DB에 있으므로 변환 설정을 바꾸면 쓰는 바이트와 읽는 방식이 바뀐다. 설정을 바꾸지 않는다.
 */
@Slf4j
public final class ChatStoredJson {

    /**
     * 알림 값 record를 쓰고 읽는 JSON 변환기. 쓰기는 기본 설정이고, 아래 설정은 읽기에만 작용한다.
     * <ul>
     *     <li>같은 버전에서 더한 키는 무시하고 읽는다.</li>
     *     <li>타입이 다른 값을 바꿔 읽지 않는다(문자열 금액을 숫자로, 숫자 닉네임을 문자열로, 소수 버전을 정수로 바꾸지 않는다).
     *     잘못된 값이 v1 값처럼 나가면 프론트가 대체 표시를 쓸 수 없기 때문이다.</li>
     *     <li>키가 없거나 null이면 실패로 본다(기본 타입을 0으로 채우지 않는다).</li>
     * </ul>
     */
    private static final JsonMapper NOTIFICATION_DATA = JsonMapper.builder()
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

    /** 초대 결과 목록을 쓰고 읽는 JSON 변환기(기본 설정) */
    private static final JsonMapper INVITE_OUTCOMES = JsonMapper.builder().build();
    private static final TypeReference<List<ChatGroupInviteOutcome>> OUTCOMES = new TypeReference<>() {
    };

    private ChatStoredJson() {
    }

    /** 알림 종류별 record를 저장할 JSON으로 바꾼다. 값이 없으면 JSON null을 저장하지 않고 실패한다 */
    public static String writeNotificationData(Object data) {
        Assert.state(data != null, "알림 값이 비어 있습니다.");
        return NOTIFICATION_DATA.writeValueAsString(data);
    }

    /** 출연 요청 도착 알림의 값. 다른 종류의 메시지이거나 값이 없거나 읽을 수 없으면 null이다 */
    public static AppearanceRequestSentData readAppearanceRequestSent(ChatMessage message) {
        return readNotificationData(message, NotificationKind.APPEARANCE_REQUEST_SENT,
                AppearanceRequestSentData.class);
    }

    /** 출연 모집 지원 도착 알림의 값. 다른 종류의 메시지이거나 값이 없거나 읽을 수 없으면 null이다 */
    public static AppearanceApplicationSubmittedData readAppearanceApplicationSubmitted(ChatMessage message) {
        return readNotificationData(message, NotificationKind.APPEARANCE_APPLICATION_SUBMITTED,
                AppearanceApplicationSubmittedData.class);
    }

    /** 단체방 초대 안내의 값. 다른 종류의 메시지이거나 읽을 수 없으면 null이다 */
    public static GroupMembersInvitedData readGroupMembersInvited(ChatMessage message) {
        return readNotificationData(message, NotificationKind.GROUP_MEMBERS_INVITED, GroupMembersInvitedData.class);
    }

    /** 단체방 강퇴 안내의 값. 다른 종류의 메시지이거나 읽을 수 없으면 null이다 */
    public static GroupMemberKickedData readGroupMemberKicked(ChatMessage message) {
        return readNotificationData(message, NotificationKind.GROUP_MEMBER_KICKED, GroupMemberKickedData.class);
    }

    /** 단체방 나가기 안내의 값. 다른 종류의 메시지이거나 읽을 수 없으면 null이다 */
    public static GroupMemberLeftData readGroupMemberLeft(ChatMessage message) {
        return readNotificationData(message, NotificationKind.GROUP_MEMBER_LEFT, GroupMemberLeftData.class);
    }

    /** 단체방 폐쇄 안내의 값. 다른 종류의 메시지이거나 읽을 수 없으면 null이다 */
    public static GroupRoomClosedData readGroupRoomClosed(ChatMessage message) {
        return readNotificationData(message, NotificationKind.GROUP_ROOM_CLOSED, GroupRoomClosedData.class);
    }

    /** 단체방 호스트 자격 상실 안내의 값. 다른 종류의 메시지이거나 읽을 수 없으면 null이다 */
    public static GroupHostUnavailableData readGroupHostUnavailable(ChatMessage message) {
        return readNotificationData(message, NotificationKind.GROUP_HOST_UNAVAILABLE, GroupHostUnavailableData.class);
    }

    /** 회원별 초대 결과 목록을 저장할 JSON으로 바꾼다. 목록이 없으면 JSON null을 저장하지 않고 실패한다 */
    public static String writeInviteOutcomes(List<ChatGroupInviteOutcome> outcomes) {
        Assert.state(outcomes != null, "초대 결과가 필요합니다.");
        return INVITE_OUTCOMES.writeValueAsString(outcomes);
    }

    /** 저장한 회원별 초대 결과 목록 */
    public static List<ChatGroupInviteOutcome> readInviteOutcomes(ChatGroupInviteRequest request) {
        try {
            return INVITE_OUTCOMES.readValue(request.getResultsJson(), OUTCOMES);
        } catch (JacksonException e) {
            // 서버가 쓴 값만 있으므로 오지 않는 경로다. 저장한 결과를 모른 채 다시 초대하지 않도록 실패로 둔다
            throw new IllegalStateException("저장한 초대 결과를 읽을 수 없습니다. id=" + request.getId(), e);
        }
    }

    /**
     * 저장한 알림 값을 알림 종류의 record로 읽는다. 메시지가 그 종류가 아니거나 값이 없으면(일반·시스템 메시지, 문구로 남긴 기존 알림)
     * null이다. 읽을 수 없는 값도 이력 조회를 막지 않도록 로그만 남기고 null로 둔다(프론트는 {@code content}를 보여 준다).
     */
    private static <T> T readNotificationData(ChatMessage message, NotificationKind kind, Class<T> type) {
        String json = message.getNotificationDataJson();
        if (message.getNotificationKind() != kind || json == null) {
            return null;
        }
        try {
            return NOTIFICATION_DATA.readValue(json, type);
        } catch (JacksonException e) {
            log.warn("알림 값을 읽을 수 없어 비워 둠 type={}, error={}", type.getSimpleName(), e.getClass().getSimpleName());
            return null;
        }
    }
}
