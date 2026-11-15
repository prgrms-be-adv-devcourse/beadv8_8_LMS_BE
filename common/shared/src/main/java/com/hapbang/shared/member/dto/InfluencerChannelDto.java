package com.hapbang.shared.member.dto;

/**
 * 인증된 채널 하나 (#769). 팔로워 수는 회원 앱의 팔로워 수 갱신이 저장한 값이다
 *
 * @param channelName   채널 이름
 * @param followerCount 팔로워 수
 */
public record InfluencerChannelDto(String channelName, int followerCount) {
}
