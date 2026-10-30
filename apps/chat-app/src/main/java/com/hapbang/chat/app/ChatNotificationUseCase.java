package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatRoomUseCase.OpenedRoom;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.out.ChatMessageRepository;
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
 *     <li>알림 메시지는 저장 시점 방의 현재 불변 수신자 집합으로 전달하고, 받는 사람(보낸 사람 제외)에게 알린다.
 *     전달 outbox·발행 체크포인트를 같은 트랜잭션에 남긴다.</li>
 *     <li>같은 출연 건의 같은 알림은 한 번만 남긴다. 이벤트가 다시 처리되면 아무것도 하지 않는다.</li>
 *     <li>두 사람 중 채팅할 수 없는 회원(정지·탈퇴, 일반 유저)이 있으면 알림을 남기지 않는다. 채팅 회원 복제본이 아직 없으면
 *     실패로 남겨 이벤트 재처리 때 다시 시도한다.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatNotificationUseCase {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatRoomSupporter chatRoomSupporter;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatUserRepository chatUserRepository;
    private final ChatDeliveryRecorder chatDeliveryRecorder;

    /** 출연 요청 도착: 호스트가 게스트에게 */
    public Optional<ChatNotificationResult> notifyAppearanceRequestSent(AppearanceRequestDto request) {
        return notify(NotificationKind.APPEARANCE_REQUEST_SENT, request.requestId(), null, request.hostId(),
                request.guestId(), nickname -> """
                        %s님이 출연을 요청했습니다.
                        제목: %s
                        출연료: %,d HP
                        방송 예정: %s
                        수락 기한: %s""".formatted(nickname, request.title(), request.fee(),
                        DATE_TIME.format(request.scheduledAt()), DATE_TIME.format(request.acceptDeadline())));
    }

    /** 출연 모집 지원 도착: 게스트가 호스트에게 */
    public Optional<ChatNotificationResult> notifyAppearanceApplicationSubmitted(AppearanceApplicationDto application) {
        return notify(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED, application.applicationId(),
                application.recruitId(), application.guestId(), application.hostId(), nickname -> """
                        %s님이 출연 모집에 지원했습니다.
                        모집글: %s""".formatted(nickname, application.recruitTitle()));
    }

    /**
     * @param parentReferenceId 알림 대상의 상위 출연 건 ID (지원 알림의 모집글 ID). 없으면 null
     */
    private Optional<ChatNotificationResult> notify(NotificationKind kind, Long referenceId, Long parentReferenceId,
            Long senderId, Long receiverId, Function<String, String> contentOf) {
        if (chatMessageRepository.existsByNotificationKindAndReferenceId(kind, referenceId)) {
            log.info("이미 남긴 채팅 알림 kind={}, referenceId={}", kind, referenceId);
            return Optional.empty();
        }
        if (!canChat(senderId) || !canChat(receiverId)) {
            log.info("채팅할 수 없는 회원이라 알림을 남기지 않음 kind={}, referenceId={}, senderId={}, receiverId={}",
                    kind, referenceId, senderId, receiverId);
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        OpenedRoom opened = chatRoomUseCase.openDirectRoom(senderId, receiverId, now);
        ChatMessage message = chatMessageRepository.save(ChatMessage.notification(opened.chatRoom(), senderId, kind,
                referenceId, parentReferenceId, contentOf.apply(chatRoomSupporter.displayNicknameOf(senderId)), now));
        chatDeliveryRecorder.recordMessage(message, opened.chatRoom().getCurrentRecipientSetId(),
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, now);
        log.info("채팅 알림 kind={}, referenceId={}, chatRoomId={}", kind, referenceId, opened.chatRoom().getId());
        // 전달 대상 목록을 만들지 않는다. 알림 메시지·재입장 안내의 전달은 같은 트랜잭션의 outbox가 맡는다(설계 4.6)
        return Optional.of(new ChatNotificationResult(ChatMessageResponse.from(message), opened.rejoinNotices()));
    }

    /** 채팅할 수 있는 회원인지. 복제본이 아직 없으면 실패로 남겨 재처리 때 다시 시도한다 */
    private boolean canChat(Long userId) {
        return chatUserRepository.findById(userId)
                .map(ChatUser::canChat)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_USER_NOT_FOUND));
    }
}
