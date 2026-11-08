package com.hapbang.chat.app.dto;

import java.util.Map;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatUser;

/**
 * 일반 메시지의 발신자 표시 정보(설계 3.6·Q37). 참여자 페이지에 없는 발신자(나간·강퇴된 회원)도 이름을 표시할 수 있게 메시지에 싣는다.
 * 값은 채팅 회원 복제본에서 읽는다. 시스템 안내·알림은 행위자를 값에 따로 담으므로 발신자 정보를 싣지 않는다(null).
 *
 * @param userId    발신자 회원 ID
 * @param nickname  표시용 닉네임. 탈퇴한 회원은 "탈퇴한 회원"이다. 복제 정보가 없으면 null이고 프론트가 회원 ID로 표시한다
 * @param withdrawn 탈퇴한 회원인지. 프론트가 탈퇴 표시 문구를 직접 만들 때 쓴다
 */
public record ChatMessageSender(
        Long userId,
        String nickname,
        boolean withdrawn
) {

    public static ChatMessageSender of(Long userId, ChatUser user) {
        if (user == null) {
            return new ChatMessageSender(userId, null, false);
        }
        return new ChatMessageSender(userId, user.getDisplayNickname(), user.isWithdrawn());
    }

    /** 발신자 정보를 싣는 메시지인지: 회원이 보낸 일반 메시지만 */
    public static boolean applies(ChatMessage message) {
        return message.getMessageType() == ChatMessageType.TEXT && message.getSenderUserId() != null;
    }

    /** 미리 묶어 읽은 회원 복제본으로 발신자 정보를 만든다. 일반 메시지가 아니면 null */
    public static ChatMessageSender forMessage(ChatMessage message, Map<Long, ChatUser> users) {
        return applies(message) ? of(message.getSenderUserId(), users.get(message.getSenderUserId())) : null;
    }
}
