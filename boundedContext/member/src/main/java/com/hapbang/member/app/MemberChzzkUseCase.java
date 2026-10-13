package com.hapbang.member.app;

import org.springframework.stereotype.Service;

import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.ChzzkUser;

import lombok.RequiredArgsConstructor;

/** 치지직에서 본인 채널 확인. DB를 쓰지 않으므로 트랜잭션 밖에서 호출한다 (기다리는 동안 DB 커넥션을 잡지 않게) */
@Service
@RequiredArgsConstructor
public class MemberChzzkUseCase {

    private final ChzzkApiClient chzzkApiClient;

    public String authorizeUrl(String state) {
        return chzzkApiClient.authorizeUrl(state);
    }

    /** code → 토큰 → 내 채널 → 팔로워 수. 토큰은 저장하지 않고 성공·실패와 상관없이 폐기한다 */
    public ChzzkChannel fetchChannel(String code, String state) {
        String accessToken = chzzkApiClient.issueToken(code, state);
        try {
            ChzzkUser me = chzzkApiClient.findMe(accessToken);
            int followerCount = chzzkApiClient.findFollowerCount(me.channelId());
            return new ChzzkChannel(me.channelId(), me.channelName(), followerCount);
        } finally {
            chzzkApiClient.revokeToken(accessToken);
        }
    }

    /** 이미 확인한 채널의 팔로워 수 다시 조회 (사용자 토큰 없이 Client 인증) */
    public int fetchFollowerCount(String channelIdentifier) {
        return chzzkApiClient.findFollowerCount(channelIdentifier);
    }
}
