package com.hapbang.auxiliary.search.influencer.out;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 검색이 회원 앱에서 읽는 회원 목록(#773 처음 적재·재색인, 내부 API {@code docs/api/internal-members.md} 1장). 테스트는 가짜로 바꿔 끼운다.
 * 실패는 {@code MemberApiCallException}(shared)으로 나간다.
 */
public interface MemberListSource {

    /** 모든 역할·상태의 회원 한 페이지(회원 ID 오름차순 키셋). afterId가 null이면 처음부터 */
    MemberPageDto members(Long afterId, int size);
}
