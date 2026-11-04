package com.hapbang.chat.app.dto;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import java.time.Instant;
import java.util.UUID;

/**
 * 연결 제어 채널({@code chat:control:v1})의 payload.
 *
 * @param controlId     제어 outbox의 안정 ID. 재시도·재적재해도 같다
 * @param memberVersion 제어 작업을 남긴 회원 원본 버전. 버전 없는 회원 정보면 null
 * @param loggedOutAt   로그아웃 작업({@code LOGOUT_USER})이 닫을 연결의 기준 시각. 이보다 먼저 인증한 연결만 닫는다. 재검사 작업과
 *                      이 값이 생기기 전의 로그아웃 작업은 null이고, null인 로그아웃은 그 회원의 연결을 모두 닫는다
 */
public record ControlFanout(
        UUID controlId,
        ChatControlType type,
        Long userId,
        Long memberVersion,
        ChatControlReason reason,
        Instant loggedOutAt
) {
}
