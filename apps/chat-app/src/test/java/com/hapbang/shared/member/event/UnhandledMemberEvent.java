package com.hapbang.shared.member.event;

/**
 * chat-app 테스트 전용. 회원 이벤트 신뢰 패키지 바로 안에 있지만 chat-app에 처리기가 없는 종류를 흉내 낸다
 * (회원 쪽이 같은 토픽에 새 이벤트를 먼저 더한 경우). shared 모듈의 코드가 아니다.
 *
 * @param userId 회원 ID
 * @param detail 로그에 남으면 안 되는 회원 정보 자리
 */
public record UnhandledMemberEvent(Long userId, String detail) {
}
