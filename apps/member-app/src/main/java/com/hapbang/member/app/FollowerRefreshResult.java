package com.hapbang.member.app;

/**
 * 팔로워 갱신 결과 (로그·테스트용).
 * missing: 치지직 결과에 없어 그대로 둔 채널 수, failedChunks: 조회에 실패한 묶음 수, stopped: 앱 키 오류(401)로 중단됨
 */
public record FollowerRefreshResult(int targets, int updated, int missing, int failedChunks, boolean stopped) {
}
