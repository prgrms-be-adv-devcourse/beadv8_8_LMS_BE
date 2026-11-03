package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.GroupHostUnavailableData;
import com.hapbang.chat.domain.GroupMemberKickedData;
import com.hapbang.chat.domain.GroupMemberLeftData;
import com.hapbang.chat.domain.GroupMembersInvitedData;
import com.hapbang.chat.domain.GroupRoomClosedData;
import com.hapbang.chat.domain.NotificationKind;

/**
 * 메시지 응답 (REST 이력·방 목록의 마지막 메시지·STOMP 프레임 공용).
 * <p>
 * 알림·단체방 안내의 값은 종류마다 필드 하나를 두고 {@code notificationKind}에 맞는 필드만 채운다(나머지는 null). 프론트가 종류별
 * 템플릿으로 문구를 만들고, 값이 있는 메시지의 {@code content}는 빈 문자열이다. 문구로 남긴 기존 알림은 종류별 필드가 모두 null이고
 * {@code content}를 보여 준다. 계약: {@code apps/chat-app/docs/contracts/notification-data.md}
 *
 * @param clientMessageId                보낸 사람이 STOMP 전송에 붙인 전송 ID. 보낸 사람 본인에게만 담고(실시간 전달·이력 조회·방 목록),
 *                                       다른 참여자에게는 null
 * @param notificationKind               알림 메시지({@code NOTIFICATION})·단체방 안내({@code SYSTEM})의 종류. 다른 메시지는 null
 * @param referenceId                    알림이 가리키는 출연 건 ID (요청서 ID 또는 지원 ID). 다른 메시지는 null
 * @param parentReferenceId              알림 대상의 상위 출연 건 ID (지원 알림의 모집글 ID). 없으면 null
 * @param appearanceRequestSent          출연 요청 도착 알림({@code APPEARANCE_REQUEST_SENT})의 값. 다른 메시지는 null
 * @param appearanceApplicationSubmitted 출연 모집 지원 도착 알림({@code APPEARANCE_APPLICATION_SUBMITTED})의 값. 다른 메시지는 null
 * @param groupMembersInvited            단체방 초대 안내({@code GROUP_MEMBERS_INVITED})의 값. 다른 메시지는 null
 * @param groupMemberKicked              단체방 강퇴 안내({@code GROUP_MEMBER_KICKED})의 값. 다른 메시지는 null
 * @param groupMemberLeft                단체방 나가기 안내({@code GROUP_MEMBER_LEFT})의 값. 다른 메시지는 null
 * @param groupRoomClosed                단체방 폐쇄 안내({@code GROUP_ROOM_CLOSED})의 값. 다른 메시지는 null
 * @param groupHostUnavailable           단체방 호스트 자격 상실 안내({@code GROUP_HOST_UNAVAILABLE})의 값. 다른 메시지는 null
 * @param sender                         일반 메시지의 발신자 표시 정보({@link ChatMessageSender}). 시스템 안내·알림은 null. REST 이력·방 목록은
 *                                       조회 시점의 복제본, 실시간 프레임은 전달 시점의 복제본 값이다
 * @param unreadMemberCount              일반 메시지({@code TEXT})를 아직 읽지 않은 현재 참여자 수(보낸 사람 제외). 시스템 안내·알림과
 *                                       집계하지 않은 응답(방 목록의 마지막 메시지·관리자 기록·재전송 응답)은 null. 한 번 낸 값은 줄기만 한다.
 *                                       계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 * @param readCountVersion               {@code unreadMemberCount}를 센 시점(DB 시각, epoch 마이크로초). 그 값이 null이면 null
 */
public record ChatMessageResponse(
        Long messageId,
        Long chatRoomId,
        long roomSequence,
        Long senderUserId,
        ChatMessageType messageType,
        String content,
        LocalDateTime sentAt,
        String clientMessageId,
        NotificationKind notificationKind,
        Long referenceId,
        Long parentReferenceId,
        AppearanceRequestSentData appearanceRequestSent,
        AppearanceApplicationSubmittedData appearanceApplicationSubmitted,
        GroupMembersInvitedData groupMembersInvited,
        GroupMemberKickedData groupMemberKicked,
        GroupMemberLeftData groupMemberLeft,
        GroupRoomClosedData groupRoomClosed,
        GroupHostUnavailableData groupHostUnavailable,
        ChatMessageSender sender,
        Integer unreadMemberCount,
        Long readCountVersion
) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getChatRoom().getId(), message.getRoomSequence(),
                message.getSenderUserId(), message.getMessageType(), message.getContent(), message.getSentAt(), null,
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId(),
                message.readAppearanceRequestSent(), message.readAppearanceApplicationSubmitted(),
                message.readGroupMembersInvited(), message.readGroupMemberKicked(), message.readGroupMemberLeft(),
                message.readGroupRoomClosed(), message.readGroupHostUnavailable(), null, null, null);
    }

    /** 발신자 정보를 함께 담은 응답. 일반 메시지가 아니면 발신자 정보는 null이다 */
    public static ChatMessageResponse from(ChatMessage message, Map<Long, ChatUser> users) {
        return from(message).withSender(ChatMessageSender.forMessage(message, users));
    }

    /** 조회한 회원 기준 응답. 본인이 보낸 메시지에만 전송 ID를 담는다 */
    public static ChatMessageResponse of(ChatMessage message, Long viewerUserId) {
        ChatMessageResponse response = from(message);
        return message.isSentBy(viewerUserId) ? response.withClientMessageId(message.getClientMessageId()) : response;
    }

    /** 조회한 회원 기준 응답에 미리 묶어 읽은 회원 복제본으로 발신자 정보를 담는다 */
    public static ChatMessageResponse of(ChatMessage message, Long viewerUserId, Map<Long, ChatUser> users) {
        return of(message, viewerUserId).withSender(ChatMessageSender.forMessage(message, users));
    }

    public ChatMessageResponse withSender(ChatMessageSender sender) {
        return new ChatMessageResponse(messageId, chatRoomId, roomSequence, senderUserId, messageType, content, sentAt,
                clientMessageId, notificationKind, referenceId, parentReferenceId, appearanceRequestSent,
                appearanceApplicationSubmitted, groupMembersInvited, groupMemberKicked, groupMemberLeft,
                groupRoomClosed, groupHostUnavailable, sender, unreadMemberCount, readCountVersion);
    }

    public ChatMessageResponse withClientMessageId(String clientMessageId) {
        return new ChatMessageResponse(messageId, chatRoomId, roomSequence, senderUserId, messageType, content, sentAt,
                clientMessageId, notificationKind, referenceId, parentReferenceId, appearanceRequestSent,
                appearanceApplicationSubmitted, groupMembersInvited, groupMemberKicked, groupMemberLeft,
                groupRoomClosed, groupHostUnavailable, sender, unreadMemberCount, readCountVersion);
    }

    /**
     * 안 읽은 사람 수를 담는다. 일반 메시지({@code TEXT})에만 담고, 그 밖의 메시지는 둘 다 null로 둔다.
     *
     * @param unreadMemberCount 센 값. 세지 않았으면 null
     * @param readCountVersion  센 시점(DB 시각, epoch 마이크로초)
     */
    public ChatMessageResponse withReadCount(Integer unreadMemberCount, Long readCountVersion) {
        boolean counted = messageType == ChatMessageType.TEXT && unreadMemberCount != null;
        return new ChatMessageResponse(messageId, chatRoomId, roomSequence, senderUserId, messageType, content, sentAt,
                clientMessageId, notificationKind, referenceId, parentReferenceId, appearanceRequestSent,
                appearanceApplicationSubmitted, groupMembersInvited, groupMemberKicked, groupMemberLeft,
                groupRoomClosed, groupHostUnavailable, sender, counted ? unreadMemberCount : null,
                counted ? readCountVersion : null);
    }
}
