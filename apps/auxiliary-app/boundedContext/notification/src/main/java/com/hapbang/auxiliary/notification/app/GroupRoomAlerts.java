package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hapbang.auxiliary.notification.app.GroupRoomAlertTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;

/**
 * N9 채팅 단체방 3종(#921, ND-127)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 초대는 이번 초대로 참여한 회원마다, 강퇴는 강퇴된 회원, 폐쇄는 폐쇄한 호스트를 뺀 참여자마다다(ND-011). 폐쇄 때
 *     호스트만 남았으면 빈 목록이다(알림 없이 커밋).</li>
 *     <li>원본 대상 ID는 계약의 사건 ID다: 초대 {@code <chatRoomId>:<inviteRequestId>}, 강퇴
 *     {@code <chatRoomId>:<kickedUserId>:<participationVersion>}, 폐쇄 {@code chatRoomId}(ND-128). 받는 사람이 여럿이어도 같은
 *     값이고, 유일 키에 받는 사람이 있어 각 1건이다(ND-019).</li>
 *     <li>이동 대상은 초대·폐쇄가 {@code CHAT_ROOM}/방 ID이고 강퇴는 없다(ND-129, 강퇴된 회원은 방을 볼 수 없다).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다(ND-021).</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 소비자는 재시도 없이 DLT로 보낸다(ND-010). 받는 사람 목록이
 *     비어도 같다(계약상 초대는 참여한 회원이 있을 때만, 폐쇄는 호스트를 포함해 낸다).</li>
 * </ul>
 */
public final class GroupRoomAlerts {

    static final String TARGET_CHAT_ROOM = "CHAT_ROOM";

    private GroupRoomAlerts() {
    }

    /** 단체방 초대: 이번 초대로 참여한 회원마다 */
    public static List<NotificationDraft> invited(GroupMembersInvitedEvent event) {
        Long room = require(event.chatRoomId(), "chatRoomId");
        String sourceTargetId = room + ":" + require(event.inviteRequestId(), "inviteRequestId");
        String title = require(event.title(), "title");
        Instant occurredAt = require(event.invitedAt(), "invitedAt");
        return receivers(event.invitedUserIds(), "invitedUserIds").stream()
                .map(receiver -> new NotificationDraft(receiver, GroupRoomAlertTemplates.GROUP_MEMBERS_INVITED.kind(),
                        event.getClass().getSimpleName(), sourceTargetId, TARGET_CHAT_ROOM, String.valueOf(room),
                        payload(title), occurredAt))
                .toList();
    }

    /** 단체방 강퇴: 강퇴된 회원에게. 이동 대상은 없다 */
    public static NotificationDraft kicked(GroupMemberKickedEvent event) {
        Long room = require(event.chatRoomId(), "chatRoomId");
        Long kickedUserId = require(event.kickedUserId(), "kickedUserId");
        if (event.participationVersion() < 1) {
            throw new IllegalArgumentException("채팅 이벤트의 참여 세대가 1보다 작습니다: " + event.participationVersion());
        }
        return new NotificationDraft(kickedUserId, GroupRoomAlertTemplates.GROUP_MEMBER_KICKED.kind(),
                event.getClass().getSimpleName(), room + ":" + kickedUserId + ":" + event.participationVersion(), null,
                null,
                payload(require(event.title(), "title")), require(event.kickedAt(), "kickedAt"));
    }

    /** 단체방 폐쇄: 폐쇄한 호스트를 뺀 참여자마다. 호스트만 남았으면 빈 목록이다 */
    public static List<NotificationDraft> closed(GroupRoomClosedEvent event) {
        Long room = require(event.chatRoomId(), "chatRoomId");
        Long host = require(event.hostUserId(), "hostUserId");
        String title = require(event.title(), "title");
        Instant occurredAt = require(event.closedAt(), "closedAt");
        return receivers(event.memberUserIds(), "memberUserIds").stream()
                .filter(member -> !member.equals(host))
                .map(member -> new NotificationDraft(member, GroupRoomAlertTemplates.GROUP_ROOM_CLOSED.kind(),
                        event.getClass().getSimpleName(), String.valueOf(room), TARGET_CHAT_ROOM, String.valueOf(room),
                        payload(title), occurredAt))
                .toList();
    }

    /** 받는 사람마다 따로 만든다(초안끼리 값 Map을 함께 쓰지 않는다) */
    private static Map<String, Object> payload(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, GroupRoomAlertTemplates.PAYLOAD_VERSION);
        payload.put(Keys.TITLE, title);
        return payload;
    }

    private static List<Long> receivers(List<Long> ids, String name) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("채팅 이벤트의 받는 사람 목록이 비었습니다: " + name);
        }
        return ids;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("채팅 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}
