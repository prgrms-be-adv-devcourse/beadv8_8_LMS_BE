package com.hapbang.auxiliary.search.out;

import java.time.LocalDateTime;

import com.hapbang.shared.member.dto.MemberChangePageDto;

/**
 * 검색이 회원 앱에서 읽는 회원 변경분(#772, 내부 API #769). 테스트는 가짜로 바꿔 끼운다.
 * 실패는 {@code MemberApiCallException}(shared)으로 나간다.
 */
public interface MemberChangeSource {

    /**
     * (updatedAt, 회원 ID)가 커서보다 뒤인 회원 한 페이지. 모든 역할·상태를 준다.
     *
     * @param afterUpdatedAt 커서 시각(회원 앱 시간대, 오프셋 없음)
     * @param afterId        커서 회원 ID
     */
    MemberChangePageDto changes(LocalDateTime afterUpdatedAt, long afterId, int size);
}
