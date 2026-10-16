package com.hapbang.chat.app.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;

/**
 * 관리자 채팅 기록의 커서 (#263). 메시지와 입퇴장 기록을 하나의 시간순 흐름으로 보고, 마지막으로 내려준 항목의 위치를 담는다.
 * <p>
 * 정렬 기준은 (시각, 종류, ID)다. 같은 시각이면 입퇴장 기록이 메시지보다 먼저다(예: 퇴장 기록 → 퇴장 안내 메시지).
 * 프론트에는 불투명한 문자열로 내려준다.
 *
 * @param at   마지막 항목의 시각 (메시지는 보낸 시각, 기록은 일어난 시각)
 * @param kind 마지막 항목의 종류
 * @param id   마지막 항목의 ID (메시지 ID 또는 기록 ID)
 */
public record ChatHistoryCursor(LocalDateTime at, Kind kind, Long id) {

    private static final String SEPARATOR = "|";

    public enum Kind {
        /** 입퇴장 기록. 같은 시각이면 메시지보다 먼저 */
        LOG,
        /** 메시지 */
        MESSAGE
    }

    public String encode() {
        String raw = at + SEPARATOR + kind + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 커서 문자열을 읽는다. 형식이 틀리면 INVALID_REQUEST */
    public static ChatHistoryCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\\" + SEPARATOR);
            if (parts.length != 3) {
                throw new ChatException(ChatErrorCode.INVALID_REQUEST, "채팅 기록 커서가 올바르지 않습니다.");
            }
            return new ChatHistoryCursor(LocalDateTime.parse(parts[0]), Kind.valueOf(parts[1]), Long.valueOf(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST, "채팅 기록 커서가 올바르지 않습니다.");
        }
    }

    /** 이 커서 뒤의 메시지 조회 조건: 같은 시각이면 이 ID보다 큰 메시지 (기록 커서면 같은 시각 메시지 전부) */
    public long messageIdAfter() {
        return kind == Kind.MESSAGE ? id : 0L;
    }

    /** 이 커서 뒤의 기록 조회 조건: 같은 시각이면 이 ID보다 큰 기록 (메시지 커서면 같은 시각 기록은 이미 지남) */
    public long logIdAfter() {
        return kind == Kind.LOG ? id : Long.MAX_VALUE;
    }
}
