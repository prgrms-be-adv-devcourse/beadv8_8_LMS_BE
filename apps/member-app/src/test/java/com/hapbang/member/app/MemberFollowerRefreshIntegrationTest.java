package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 팔로워 수 일일 갱신: 실제 DB, 치지직은 가짜로 대신한다 (#302) */
@MemberIntegrationTest
class MemberFollowerRefreshIntegrationTest {

    private static final LocalDateTime REGISTERED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final Long ADMIN_ID = 99L;

    @Autowired
    MemberFollowerRefreshUseCase useCase;

    @Autowired
    UserRepository userRepository;

    @MockitoSpyBean
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    ChzzkApiClient chzzkApiClient;

    @Test
    @DisplayName("정상 회원의 채널만 갱신한다: 팔로워 수·조회 시각이 바뀌고, 탈퇴 회원 채널은 치지직에 묻지도 않는다")
    void refreshActiveMembersOnly() {
        InfluencerChannel active = registered(userRepository.save(MemberFixture.user()), "active-1", 5);
        InfluencerChannel withdrawn = registered(
                userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN)), "withdrawn-1", 5);
        given(chzzkApiClient.findFollowerCounts(List.of("active-1"))).willReturn(Map.of("active-1", 30));
        LocalDateTime before = LocalDateTime.now();

        FollowerRefreshResult result = useCase.refreshAll();

        assertThat(result).isEqualTo(new FollowerRefreshResult(1, 1, 0, 0, false));
        InfluencerChannel refreshed = find(active);
        assertThat(refreshed.getFollowerCount()).isEqualTo(30);
        assertThat(refreshed.getFollowerCountUpdatedAt()).isAfterOrEqualTo(before.withNano(0));
        assertThat(find(withdrawn).getFollowerCount()).isEqualTo(5);
        assertThat(find(withdrawn).getFollowerCountUpdatedAt()).isEqualTo(REGISTERED_AT);
    }

    @Test
    @DisplayName("팔로워 수·조회 시각 두 칸만 바뀌고 승인·반려 채널의 상태·심사 정보·반려 사유는 그대로다")
    void onlyTwoColumnsChange() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel approved = registered(user, "approved-1", 15);
        InfluencerChannel rejected = pending(user, "rejected-1", 3);
        rejected.reject(ADMIN_ID, "활동 내역 부족", REGISTERED_AT);
        influencerChannelRepository.save(rejected);
        given(chzzkApiClient.findFollowerCounts(List.of("approved-1", "rejected-1")))
                .willReturn(Map.of("approved-1", 40, "rejected-1", 8));

        useCase.refreshAll();

        InfluencerChannel savedApproved = find(approved);
        assertThat(savedApproved.getFollowerCount()).isEqualTo(40);
        assertThat(savedApproved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        InfluencerChannel savedRejected = find(rejected);
        assertThat(savedRejected.getFollowerCount()).isEqualTo(8);
        assertThat(savedRejected.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.REJECTED);
        assertThat(savedRejected.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(savedRejected.getRejectionReason()).isEqualTo("활동 내역 부족");
    }

    @Test
    @DisplayName("배치가 대상을 읽은 뒤 관리자가 승인해도 승인은 그대로고 팔로워 수만 바뀐다")
    void adminApprovalDuringRefresh() {
        InfluencerChannel channel = pending(userRepository.save(MemberFixture.user()), "pending-1", 5);
        // 치지직 응답 직전(대상은 이미 읽었고 아직 저장 전)에 관리자 승인이 커밋된다
        willAnswer(invocation -> {
            transactionTemplate.executeWithoutResult(status -> {
                InfluencerChannel loaded = influencerChannelRepository.findById(channel.getId()).orElseThrow();
                loaded.approve(ADMIN_ID, LocalDateTime.now());
            });
            return Map.of("pending-1", 7);
        }).given(chzzkApiClient).findFollowerCounts(List.of("pending-1"));

        useCase.refreshAll();

        InfluencerChannel saved = find(channel);
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(saved.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(saved.getFollowerCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("치지직 결과에 없는 채널은 팔로워 수·조회 시각을 그대로 둔다")
    void missingChannelUnchanged() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel found = registered(user, "found-1", 5);
        InfluencerChannel missing = registered(user, "missing-1", 6);
        given(chzzkApiClient.findFollowerCounts(List.of("found-1", "missing-1"))).willReturn(Map.of("found-1", 9));

        FollowerRefreshResult result = useCase.refreshAll();

        assertThat(result).isEqualTo(new FollowerRefreshResult(2, 1, 1, 0, false));
        assertThat(find(found).getFollowerCount()).isEqualTo(9);
        assertThat(find(missing).getFollowerCount()).isEqualTo(6);
        assertThat(find(missing).getFollowerCountUpdatedAt()).isEqualTo(REGISTERED_AT);
    }

    @Test
    @DisplayName("21개면 20개·1개 두 묶음으로 나눠 조회한다")
    void chunksOfTwenty() {
        User user = userRepository.save(MemberFixture.user());
        for (int i = 1; i <= 21; i++) {
            registered(user, "channel-%02d".formatted(i), 1);
        }
        given(chzzkApiClient.findFollowerCounts(anyList())).willReturn(Map.of());

        useCase.refreshAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(chzzkApiClient, times(2)).findFollowerCounts(ids.capture());
        assertThat(ids.getAllValues()).extracting(List::size).containsExactly(20, 1);
    }

    @Test
    @DisplayName("묶음 조회가 장애로 실패하면 그 묶음만 건너뛰고 다음 묶음은 갱신한다")
    void failedChunkSkipped() {
        List<InfluencerChannel> channels = twentyOneChannels();
        List<String> firstChunk = identifiers(channels.subList(0, 20));
        given(chzzkApiClient.findFollowerCounts(firstChunk)).willThrow(new ResourceAccessException("시간 초과"));
        given(chzzkApiClient.findFollowerCounts(List.of("channel-21"))).willReturn(Map.of("channel-21", 50));

        FollowerRefreshResult result = useCase.refreshAll();

        assertThat(result).isEqualTo(new FollowerRefreshResult(21, 1, 0, 1, false));
        assertThat(find(channels.get(0)).getFollowerCount()).isEqualTo(1);
        assertThat(find(channels.get(20)).getFollowerCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("묶음 저장이 DB 오류로 실패하면 그 묶음만 롤백하고 다음 묶음은 갱신한다")
    void failedSaveSkipped() {
        List<InfluencerChannel> channels = twentyOneChannels();
        given(chzzkApiClient.findFollowerCounts(identifiers(channels.subList(0, 20)))).willReturn(Map.of(
                "channel-01", 10, "channel-02", 20));
        given(chzzkApiClient.findFollowerCounts(List.of("channel-21"))).willReturn(Map.of("channel-21", 50));
        // 첫 묶음의 두 번째 채널 저장에서 DB 오류 → 앞서 저장한 첫 채널도 함께 롤백되어야 한다
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .given(influencerChannelRepository).updateFollowerCount(eq(channels.get(1).getId()), anyInt(), any());

        FollowerRefreshResult result = useCase.refreshAll();

        assertThat(result).isEqualTo(new FollowerRefreshResult(21, 1, 0, 1, false));
        assertThat(find(channels.get(0)).getFollowerCount()).isEqualTo(1);
        assertThat(find(channels.get(20)).getFollowerCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("앱 키 오류(401)면 바로 중단하고 남은 묶음은 조회하지 않는다")
    void unauthorizedStops() {
        List<InfluencerChannel> channels = twentyOneChannels();
        given(chzzkApiClient.findFollowerCounts(identifiers(channels.subList(0, 20))))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        FollowerRefreshResult result = useCase.refreshAll();

        assertThat(result).isEqualTo(new FollowerRefreshResult(21, 0, 0, 0, true));
        verify(chzzkApiClient, times(1)).findFollowerCounts(anyList());
        assertThat(find(channels.get(20)).getFollowerCount()).isEqualTo(1);
    }

    /** 팔로워 기준(10명) 이상이면 승인, 미만이면 등록만 */
    private InfluencerChannel registered(User user, String identifier, int followerCount) {
        return influencerChannelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK, identifier,
                "채널 " + identifier, followerCount, REGISTERED_AT));
    }

    /** 승인 요청했지만 기준 미달이라 관리자 심사 대기 */
    private InfluencerChannel pending(User user, String identifier, int followerCount) {
        InfluencerChannel channel = registered(user, identifier, followerCount);
        channel.requestApproval(followerCount, REGISTERED_AT);
        return influencerChannelRepository.save(channel);
    }

    private List<InfluencerChannel> twentyOneChannels() {
        User user = userRepository.save(MemberFixture.user());
        List<InfluencerChannel> channels = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            channels.add(registered(user, "channel-%02d".formatted(i), 1));
        }
        return channels;
    }

    private static List<String> identifiers(List<InfluencerChannel> channels) {
        return channels.stream().map(InfluencerChannel::getChannelIdentifier).toList();
    }

    private InfluencerChannel find(InfluencerChannel channel) {
        return influencerChannelRepository.findById(channel.getId()).orElseThrow();
    }
}
