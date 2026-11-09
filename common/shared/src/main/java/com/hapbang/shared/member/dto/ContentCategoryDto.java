package com.hapbang.shared.member.dto;

/**
 * 콘텐츠 분야 하나 (#769)
 *
 * @param code   영문 UPPER_SNAKE 코드. 바뀌지 않는다(UserDto.contentCategories에 실리는 값)
 * @param name   한글 이름. 바뀔 수 있다
 * @param active 새로 고를 수 있는지. 꺼져도 이미 고른 회원의 값에는 남는다
 */
public record ContentCategoryDto(String code, String name, boolean active) {
}
