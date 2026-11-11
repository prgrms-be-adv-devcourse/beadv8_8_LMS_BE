package com.hapbang.auxiliary.notification.app;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 회원 복제본 Backfill이 읽는 회원 목록(ND-077). 운영은 회원 목록 내부 API(`docs/api/internal-members.md` 1장)다. 회원 ID 오름차순
 * 키셋 페이지이고 모든 역할·상태를 준다.
 */
public interface NotificationMemberSource {

    /**
     * @param afterId 이 ID보다 큰 회원부터. null이면 처음부터
     * @param size    페이지 크기(최대 {@link MemberPageDto#MAX_SIZE})
     */
    MemberPageDto members(Long afterId, int size);
}
