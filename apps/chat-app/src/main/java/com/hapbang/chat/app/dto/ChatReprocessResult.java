package com.hapbang.chat.app.dto;

import java.util.UUID;

import com.hapbang.chat.app.ChatJobStream;

/**
 * 운영 재처리 요청 결과. 되돌린 작업은 relay·재적재로 같은 안정 ID로 다시 발행된다(발행 완료를 뜻하지 않는다).
 *
 * @param requestedBy 요청한 관리자 회원 ID(감사 기록에 남긴다)
 */
public record ChatReprocessResult(ChatJobStream stream, UUID jobId, Long requestedBy) {
}
