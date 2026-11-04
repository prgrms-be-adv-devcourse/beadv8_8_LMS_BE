package com.hapbang.chat.in.dto;

import java.time.Instant;

import com.hapbang.chat.app.dto.ChatReauthResult;

/**
 * 재인증 결과 프레임. {@code /user/queue/chats/connection}으로 재인증 프레임을 보낸 연결에만 보낸다(resync-frame.md "재인증").
 *
 * @param type                {@code REAUTHENTICATED} 또는 {@code REAUTH_FAILED}
 * @param code                실패 사유({@code INVALID_TICKET}·{@code MEMBER_MISMATCH}·{@code CONNECTION_CLOSING}). 성공이면 null
 * @param connectionExpiresAt 이 연결이 닫히는 시각(ISO-8601 UTC). 성공이면 새 값, 실패면 바뀌지 않은 기존 값. 모르면 null
 */
public record ChatReauthFrame(String type, String code, Instant connectionExpiresAt) {

    public static final String REAUTHENTICATED = "REAUTHENTICATED";
    public static final String REAUTH_FAILED = "REAUTH_FAILED";

    public static ChatReauthFrame of(ChatReauthResult result) {
        if (result.succeeded()) {
            return new ChatReauthFrame(REAUTHENTICATED, null, result.connectionExpiresAt());
        }
        return new ChatReauthFrame(REAUTH_FAILED, result.outcome().name(), result.connectionExpiresAt());
    }
}
