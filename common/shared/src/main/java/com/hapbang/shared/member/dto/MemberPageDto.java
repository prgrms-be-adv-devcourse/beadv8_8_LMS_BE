package com.hapbang.shared.member.dto;

import java.util.List;
import java.util.Objects;

/**
 * 내부 회원 목록 한 페이지 (#303). 회원 ID 오름차순 키셋 페이지다.
 *
 * @param items       이 페이지의 회원 (모든 역할·상태). 이벤트와 같은 UserDto라 받는 쪽은 그대로 syncFrom에 넣으면 버전 비교가 된다
 * @param nextAfterId 다음 페이지를 부를 때 넘길 afterId. 마지막 페이지면 null
 */
public record MemberPageDto(List<UserDto> items, Long nextAfterId) {

    /** 한 번에 받을 수 있는 최대 회원 수 */
    public static final int MAX_SIZE = 500;

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다. null 항목도 받지 않는다 (강의 계약과 같은 방식)
    public MemberPageDto {
        items = List.copyOf(Objects.requireNonNull(items, "items는 필수입니다."));
    }
}
