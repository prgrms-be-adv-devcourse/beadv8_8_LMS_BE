package com.hapbang.chat.app.dto;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import java.util.UUID;

/**
 * 연결 제어 채널({@code chat:control:v1})의 payload.
 *
 * @param controlId     제어 outbox의 안정 ID. 재시도·재적재해도 같다
 * @param memberVersion 제어 작업을 남긴 회원 원본 버전. 버전 없는 회원 정보면 null
 */
public record ControlFanout(
        UUID controlId,
        ChatControlType type,
        Long userId,
        Long memberVersion,
        ChatControlReason reason
) {
}
