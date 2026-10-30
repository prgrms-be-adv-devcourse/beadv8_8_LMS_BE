package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatRoomUseCase.OpenedRoom;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.NotificationData;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 이벤트를 채팅 알림 메시지로 남긴다 (#229).
 * <ul>
 *     <li>알림은 행위자(보낸 사람)와 받는 사람의 1:1 방에 남긴다. 방이 없으면 만들고, 나갔으면 재입장시킨다
 *     ({@link ChatRoomUseCase#openDirectRoom}, 채팅 시작과 같은 규칙).</li>
 *     <li>같은 출연 건의 같은 알림은 한 번만 남긴다. 이벤트가 다시 처리되면 아무것도 하지 않는다.</li>
 *     <li>문구는 만들지 않는다. 이벤트의 값과 행위자 닉네임(알림을 남길 때의 채팅 회원 복제본 값)을 {@link NotificationData}로
 *     남기고, 프론트가 종류별 템플릿으로 문구를 만든다(사용자 결정 2026-10-30).</li>
 *     <li>두 사람 중 채팅할 수 없는 회원(정지·탈퇴, 일반 유저)이 있으면 알림을 남기지 않는다. 채팅 회원 복제본이 아직 없으면
 *     실패로 남겨 이벤트 재처리 때 다시 시도한다.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatNotificationUseCase {

    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatUserRepository chatUserRepository;

    /** 출연 요청 도착: 호스트가 게스트에게 */
    public Optional<ChatNotificationResult> notifyAppearanceRequestSent(AppearanceRequestDto request) {
        return notify(NotificationKind.APPEARANCE_REQUEST_SENT, request.requestId(), null, request.hostId(),
                request.guestId(), hostNickname -> NotificationData.appearanceRequestSent(hostNickname, request));
    }

    /** 출연 모집 지원 도착: 게스트가 호스트에게 */
    public Optional<ChatNotificationResult> notifyAppearanceApplicationSubmitted(AppearanceApplicationDto application) {
        return notify(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED, application.applicationId(),
                application.recruitId(), application.guestId(), application.hostId(),
                guestNickname -> NotificationData.appearanceApplicationSubmitted(guestNickname, application));
    }

    /**
     * @param parentReferenceId 알림 대상의 상위 출연 건 ID (지원 알림의 모집글 ID). 없으면 null
     * @param dataOf            행위자(보낸 사람) 닉네임으로 알림 값을 만든다
     */
    private Optional<ChatNotificationResult> notify(NotificationKind kind, Long referenceId, Long parentReferenceId,
            Long senderId, Long receiverId, Function<String, Map<String, Object>> dataOf) {
        if (chatMessageRepository.existsByNotificationKindAndReferenceId(kind, referenceId)) {
            log.info("이미 남긴 채팅 알림 kind={}, referenceId={}", kind, referenceId);
            return Optional.empty();
        }
        ChatUser sender = chatUserOf(senderId);
        if (!sender.canChat() || !chatUserOf(receiverId).canChat()) {
            log.info("채팅할 수 없는 회원이라 알림을 남기지 않음 kind={}, referenceId={}, senderId={}, receiverId={}",
                    kind, referenceId, senderId, receiverId);
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        OpenedRoom opened = chatRoomUseCase.openDirectRoom(senderId, receiverId, now);
        ChatMessage message = chatMessageRepository.save(ChatMessage.notification(opened.chatRoom(), senderId, kind,
                referenceId, parentReferenceId, dataOf.apply(sender.getNickname()), now));
        List<Long> deliverToUserIds = chatRoomMemberRepository
                .findByChatRoom_IdAndDeletedAtIsNull(opened.chatRoom().getId()).stream()
                .filter(ChatRoomMember::isActive)
                .map(ChatRoomMember::getUserId)
                .toList();
        log.info("채팅 알림 kind={}, referenceId={}, chatRoomId={}", kind, referenceId, opened.chatRoom().getId());
        SentChatMessage sent = new SentChatMessage(ChatMessageResponse.from(message), deliverToUserIds,
                List.of(receiverId));
        return Optional.of(new ChatNotificationResult(sent, opened.rejoinNotices(), opened.remainingUserIds()));
    }

    /**
     * 채팅 회원 복제본. 채팅할 수 있는지와 행위자 닉네임을 여기서 읽는다(출연 이벤트에는 닉네임이 없다).
     * 복제본이 아직 없으면 실패로 남겨 재처리 때 다시 시도한다
     */
    private ChatUser chatUserOf(Long userId) {
        return chatUserRepository.findById(userId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_USER_NOT_FOUND));
    }
}
