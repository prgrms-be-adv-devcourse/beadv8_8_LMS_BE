package com.hapbang.chat.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;

/**
 * 방 topic 구독 하나에 쓰는 거절·회수 프레임(헤더 {@code chat-frame-type}과 같은 {@code type}). resync-frame.md
 *
 * @param currentParticipationVersion 세대 불일치일 때만 받는 회원의 현재 세대
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatSubscriptionFrame(
        SubscriptionSignal type,
        Long roomId,
        ChatSubscriptionReason reason,
        Integer currentParticipationVersion
) {
}
