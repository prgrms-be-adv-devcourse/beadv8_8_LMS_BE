package com.hapbang.member.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.AppearanceRatingRepository;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;

/** 인플루언서 통계 내부 API (#769): 정상 인플루언서만 ID 순 키셋으로, 인증된 채널 모두와 출연 평점을 준다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class MemberInfluencerStatsInternalApiTest {

    private static final String STATS = "/api/v1/internal/members/influencer-stats";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository channelRepository;

    @Autowired
    AppearanceRatingRepository ratingRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("정상 인플루언서·인증된 인플루언서만 ID 순으로 나온다 (유저·관리자·정지·탈퇴 제외)")
    void onlyActiveInfluencers() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        userRepository.save(MemberFixture.member(Role.USER));
        userRepository.save(MemberFixture.member(Role.ADMIN));
        userRepository.save(MemberFixture.member(Role.INFLUENCER, UserStatus.SUSPENDED));
        userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN));
        User verified = userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER));

        mockMvc.perform(internal(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].userId").value(influencer.getId()))
                .andExpect(jsonPath("$.data.items[1].userId").value(verified.getId()))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    @DisplayName("인증된 채널을 채널 ID 순으로 모두 주고, 승인 전·삭제된 채널은 뺀다")
    void approvedChannelsOnly() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        saveChannel(influencer, "본채널", 1200, false);
        saveChannel(influencer, "등록만", 3, false);          // 팔로워 기준 미달이라 등록만 됨
        saveChannel(influencer, "삭제됨", 500, true);
        saveChannel(influencer, "다시보기", 300, false);
        User noChannel = userRepository.save(MemberFixture.member(Role.INFLUENCER));

        mockMvc.perform(internal(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].channels.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].channels[0].channelName").value("본채널"))
                .andExpect(jsonPath("$.data.items[0].channels[0].followerCount").value(1200))
                .andExpect(jsonPath("$.data.items[0].channels[1].channelName").value("다시보기"))
                .andExpect(jsonPath("$.data.items[0].channels[1].followerCount").value(300))
                // 채널 없이 인플루언서가 된 회원(관리자 지정)은 빈 배열
                .andExpect(jsonPath("$.data.items[1].userId").value(noChannel.getId()))
                .andExpect(jsonPath("$.data.items[1].channels.length()").value(0));
    }

    @Test
    @DisplayName("출연 평점은 받은 리뷰 전체의 평균·수이고, 리뷰가 없으면 평균이 없고 수는 0이다")
    void appearanceRating() throws Exception {
        User reviewed = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User notReviewed = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        // 호스트로 5점·4점, 게스트로 4점 → 전체 13/3 = 4.3
        transactionTemplate.executeWithoutResult(status ->
                ratingRepository.add(reviewed.getId(), 9, 2, 4, 1, LocalDateTime.now()));

        mockMvc.perform(internal(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].appearanceRating.average").value(4.3))
                .andExpect(jsonPath("$.data.items[0].appearanceRating.count").value(3))
                .andExpect(jsonPath("$.data.items[1].userId").value(notReviewed.getId()))
                .andExpect(jsonPath("$.data.items[1].appearanceRating.average").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].appearanceRating.count").value(0));
    }

    @Test
    @DisplayName("version은 회원 원본 값이다")
    void version() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        influencer.changeNickname("바뀐닉네임");
        userRepository.saveAndFlush(influencer);

        mockMvc.perform(internal(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].version").value(1));
    }

    @Test
    @DisplayName("afterId·size로 넘기면 다음 페이지가 이어지고, 마지막 페이지면 nextAfterId가 없다")
    void keyset() throws Exception {
        User first = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        userRepository.save(MemberFixture.member(Role.USER));
        User second = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User third = userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER));

        mockMvc.perform(internal(get(STATS).param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].userId").value(first.getId()))
                .andExpect(jsonPath("$.data.items[1].userId").value(second.getId()))
                .andExpect(jsonPath("$.data.nextAfterId").value(second.getId()));

        mockMvc.perform(internal(get(STATS).param("afterId", second.getId().toString()).param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(third.getId()))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    @DisplayName("size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST")
    void invalidRequest() throws Exception {
        for (String[] param : new String[][] {{"size", "0"}, {"size", "501"}, {"afterId", "-1"}}) {
            mockMvc.perform(internal(get(STATS).param(param[0], param[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value(param[0]));
        }
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401이다")
    void unauthorized() throws Exception {
        mockMvc.perform(get(STATS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(STATS).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
    }

    private void saveChannel(User user, String name, int followerCount, boolean deleted) {
        LocalDateTime now = LocalDateTime.now();
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK, name + user.getId(), name,
                followerCount, now);
        if (deleted) {
            ReflectionTestUtils.setField(channel, "deletedAt", now);
        }
        channelRepository.save(channel);
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }
}
