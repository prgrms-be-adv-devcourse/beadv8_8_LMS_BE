package com.hapbang.member.out;

/** 팔로워 갱신 대상 채널 (우리 채널 ID, 치지직 채널 ID) */
public record FollowerRefreshTarget(Long channelId, String channelIdentifier) {
}
