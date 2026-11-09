package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.AppearanceRatingRepository;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;

import jakarta.persistence.EntityManagerFactory;

/**
 * 인플루언서 통계가 채널·평점을 회원마다 따로 읽지 않는지(N+1) 확인한다 (#769).
 * 회원 수를 늘려도 SQL 수가 그대로면 묶어 읽은 것이다. 설정이 {@link MemberContentCategoryBatchFetchTest}와 같아 컨텍스트를 함께 쓴다
 */
@MemberIntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MemberInfluencerStatsBatchFetchTest {

    @Autowired
    MemberInternalFacade memberInternalFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository channelRepository;

    @Autowired
    AppearanceRatingRepository ratingRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    @WithMockUser(roles = "INTERNAL")
    @DisplayName("인플루언서가 2명이든 10명이든 같은 수의 SQL로 채널·평점까지 읽는다")
    void influencerStats() {
        saveInfluencers(2);
        long few = statementsOf(() -> memberInternalFacade.findInfluencerStats(null, 100));
        saveInfluencers(8);

        long many = statementsOf(() -> memberInternalFacade.findInfluencerStats(null, 100));

        assertThat(many).isEqualTo(few);
    }

    /** 채널 2개와 평점이 있는 인플루언서 */
    private void saveInfluencers(int count) {
        for (int i = 0; i < count; i++) {
            User user = userRepository.save(MemberFixture.member(Role.INFLUENCER));
            LocalDateTime now = LocalDateTime.now();
            channelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK, "main" + user.getId(),
                    "본채널", 100, now));
            channelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK, "sub" + user.getId(),
                    "다시보기", 50, now));
            transactionTemplate.executeWithoutResult(status ->
                    ratingRepository.add(user.getId(), 5, 1, 0, 0, now));
        }
    }

    private long statementsOf(Runnable action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }
}
