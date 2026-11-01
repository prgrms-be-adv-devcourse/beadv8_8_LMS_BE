package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatMessageUseCase {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatValidationService chatValidationService;
    private final ChatDeliveryRecorder chatDeliveryRecorder;

    /**
     * 메시지를 보낸다. 방을 잠그고 순번을 발급한다. 상대가 나간 동안에는 보낼 수 없다(조회만 허용).
     * <p>
     * 같은 참여 안에서 같은 전송 ID로 이미 저장한 메시지가 있으면(응답을 놓친 클라이언트의 재전송) 새로 저장하지 않고
     * 기존 메시지를 보낸 사람에게만 다시 전달한다. 본문이 달라도 기존 메시지를 돌려준다. 재입장 이전에 쓴 전송 ID는 새 메시지로 저장한다(#311). 알림·안 읽은 수는 다시 올리지 않는다(#261). 같은 방의 전송은 방 잠금으로 줄을 서므로
     * 동시 재전송도 한 번만 저장된다.
     * <p>
     * 새 메시지는 방의 현재 불변 수신자 집합을 수신자로 확정해 전달 outbox·발행 체크포인트와 함께 저장한다.
     * 재전송은 전달 작업을 만들지 않고 {@code resent=true}로 돌려준다. 호출한 쪽이 보낸 사람에게만 다시 보낸다.
     * 수신자 집합이 없는 방에는 새 메시지를 저장하지 않는다.
     *
     * @param clientMessageId 보낸 사람이 붙인 전송 ID. 없으면 중복을 확인하지 않는다
     */
    public SentChatMessage send(Long chatRoomId, Long senderId, String content, String clientMessageId) {
        validateContent(content);
        // 저장 트랜잭션(설계 4.6): 방 잠금 → 발신자 참여 행 하나 → 회원 자격 → 같은 참여의 멱등 조회 → 상대 퇴장(집계)
        // → 순번·메시지·outbox 저장. 방 참여자 전체를 읽거나 전달 대상 목록·응답을 조립하지 않는다
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(chatRoomId)
                .filter(room -> !room.isEnded())
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember sender = chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, senderId)
                .filter(ChatRoomMember::isActive)
                .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER));
        chatValidationService.validateCanChat(senderId);
        if (clientMessageId != null) {
            // 재전송은 같은 참여 안에서만 본다. 재입장 이전 메시지는 재입장 때 전송 ID를 비우지만, 한 번 더 확인한다(#311)
            Optional<ChatMessage> resent = chatMessageRepository
                    .findByChatRoom_IdAndSenderUserIdAndClientMessageId(chatRoomId, senderId, clientMessageId)
                    .filter(message -> sender.canView(message.getRoomSequence()));
            if (resent.isPresent()) {
                log.info("재전송이라 기존 메시지를 돌려줌 chatRoomId={}, messageId={}", chatRoomId, resent.get().getId());
                return new SentChatMessage(ChatMessageResponse.from(resent.get()), true,
                        sender.getParticipationVersion());
            }
        }
        if (!chatRoom.hasRecipientSet()) {
            log.error("수신자 집합이 없는 방이라 메시지를 저장하지 않음 chatRoomId={}", chatRoomId);
            throw new ChatException(ChatErrorCode.CHAT_ROOM_NOT_READY);
        }
        // 1:1 방의 상대 퇴장: 참여 변경 때 방 잠금 아래 함께 바꾸는 활성 참여자 수로 확인한다(목록을 만들지 않는다)
        if (chatRoom.getActiveMemberCount() < 2) {
            throw new ChatException(ChatErrorCode.CHAT_PARTNER_LEFT);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage message = chatMessageRepository.save(
                ChatMessage.text(chatRoom, senderId, content, clientMessageId, now));
        chatDeliveryRecorder.recordMessage(message, chatRoom.getCurrentRecipientSetId(),
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, now);
        return new SentChatMessage(ChatMessageResponse.from(message), false, sender.getParticipationVersion());
    }

    /**
     * 본문 검증. DB를 쓰지 않으므로 Facade가 저장 트랜잭션을 열기 전에 먼저 부른다(설계 4.6 "DB 트랜잭션 전").
     * UseCase를 직접 쓰는 호출도 같은 검사를 받도록 {@link #send}에서도 한 번 더 부른다.
     */
    static void validateContent(String content) {
        if (!ChatMessage.hasContent(content)) {
            throw new ChatException(ChatErrorCode.EMPTY_MESSAGE);
        }
        if (!ChatMessage.fitsLength(content)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "메시지는 " + ChatMessage.MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
    }
}
