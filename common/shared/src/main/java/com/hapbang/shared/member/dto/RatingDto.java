package com.hapbang.shared.member.dto;

/**
 * 평점 (#769). 회원 앱 프로필의 평점과 같은 값이다
 *
 * @param average 평균, 소수 첫째 자리까지. 리뷰가 없으면 null (0점과 구분)
 * @param count   리뷰 수
 */
public record RatingDto(Double average, long count) {
}
