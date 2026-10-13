package com.hapbang.member.out;

/** 치지직 로그인한 사용자의 채널 (채널 ID는 치지직 사용자 고유 ID이기도 하다) */
public record ChzzkUser(String channelId, String channelName) {
}
