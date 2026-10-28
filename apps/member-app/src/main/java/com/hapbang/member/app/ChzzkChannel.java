package com.hapbang.member.app;

/** 치지직에서 확인한 본인 채널 (채널 ID·이름은 users/me, 팔로워 수는 channels 조회) */
public record ChzzkChannel(String channelId, String channelName, int followerCount) {
}
