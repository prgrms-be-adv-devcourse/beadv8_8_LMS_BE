package com.hapbang.chat.app;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageSender;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 메시지 목록의 발신자 복제본을 한 번에 읽는다(설계 3.6·Q37). 메시지마다 회원을 따로 조회하지 않는다.
 * 일반 메시지의 발신자만 읽는다({@link ChatMessageSender#applies}). 호출한 쪽의 읽기 트랜잭션에 참여한다.
 */
@Component
@RequiredArgsConstructor
class ChatMessageSenders {

    private final ChatUserRepository chatUserRepository;

    @Transactional(readOnly = true)
    Map<Long, ChatUser> usersOf(Collection<ChatMessage> messages) {
        var senderIds = messages.stream()
                .filter(ChatMessageSender::applies)
                .map(ChatMessage::getSenderUserId)
                .distinct()
                .toList();
        if (senderIds.isEmpty()) {
            return Map.of();
        }
        return chatUserRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(ChatUser::getId, Function.identity()));
    }
}
