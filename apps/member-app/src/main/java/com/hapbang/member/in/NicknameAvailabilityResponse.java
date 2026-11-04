package com.hapbang.member.in;

/** 닉네임 중복 확인 결과 (#646). true면 쓸 수 있다 */
public record NicknameAvailabilityResponse(boolean available) {
}
