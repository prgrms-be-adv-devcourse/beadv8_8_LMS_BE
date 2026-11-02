package com.hapbang.member.app;

/** 치지직 연동 콜백 결과 (#370): 연동을 시작한 회원과 채널 등록 결과 */
public record ChzzkConnection(Long userId, ChannelVerificationResult result) {
}
