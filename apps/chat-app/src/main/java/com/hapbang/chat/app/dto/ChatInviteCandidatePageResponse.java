package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 초대 후보 한 페이지(회원 ID 오름차순). 이미 참여 중인 회원은 빠진다.
 *
 * @param nextCursor          다음 페이지 요청의 {@code cursor}. 더 없으면 null
 * @param remainingGuestSlots 지금 남은 게스트 자리 수
 */
public record ChatInviteCandidatePageResponse(List<ChatInviteCandidateResponse> candidates, Long nextCursor,
        int remainingGuestSlots) {
}
