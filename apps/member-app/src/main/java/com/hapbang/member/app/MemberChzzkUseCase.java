package com.hapbang.member.app;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.ChzzkStateStore;
import com.hapbang.member.out.ChzzkUser;

import lombok.RequiredArgsConstructor;

/** 치지직에서 본인 채널 확인. DB를 쓰지 않으므로 트랜잭션 밖에서 호출한다 (기다리는 동안 DB 커넥션을 잡지 않게) */
@Service
@RequiredArgsConstructor
public class MemberChzzkUseCase {

    private final ChzzkApiClient chzzkApiClient;
    private final ChzzkStateStore chzzkStateStore;

    /** 연동 시작 (#370): 랜덤 state를 연동하는 회원과 함께 맡기고, 치지직 화면 주소와 브라우저 ID를 준다 */
    public ChzzkAuthorization start(Long userId) {
        String state = UUID.randomUUID().toString();
        String browserId = chzzkStateStore.save(userId, state);
        return new ChzzkAuthorization(chzzkApiClient.authorizeUrl(state), browserId, ChzzkStateStore.TTL);
    }

    /**
     * 콜백 (#370): 이 브라우저에서 시작한 연동의 회원을 찾는다. 맡긴 state와 콜백의 state가 같아야 한다.
     * 꺼내면서 지우므로 한 번만 쓸 수 있다. 다른 브라우저에서 연 콜백(쿠키 없음)도 여기서 막힌다
     */
    public Long takeUserId(String browserId, String state) {
        return chzzkStateStore.take(browserId)
                .filter(saved -> saved.state().equals(state))
                .map(ChzzkStateStore.Entry::userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_CHZZK_STATE));
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
