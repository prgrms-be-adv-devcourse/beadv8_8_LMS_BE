package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;

import jakarta.persistence.EntityManagerFactory;

/**
 * 여러 회원을 읽을 때 콘텐츠 분야를 회원마다 따로 읽지 않는지(N+1) 확인한다 (#711).
 * 회원 수를 늘려도 SQL 수가 그대로면 묶어 읽은 것이다
 */
@MemberIntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MemberContentCategoryBatchFetchTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    MemberInternalFacade memberInternalFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("인플루언서 목록은 회원이 2명이든 10명이든 같은 수의 SQL로 분야까지 읽는다")
    void influencers() {
        saveInfluencers(0, 2);
        long few = statementsOf(() -> memberFacade.findInfluencers(PageRequest.of(0, 20)));
        saveInfluencers(2, 8);

        long many = statementsOf(() -> memberFacade.findInfluencers(PageRequest.of(0, 20)));

        assertThat(many).isEqualTo(few);
    }

    @Test
    @WithMockUser(roles = "INTERNAL")
    @DisplayName("내부 회원 목록은 회원이 2명이든 10명이든 같은 수의 SQL로 분야까지 읽는다")
    void internalMembers() {
        saveInfluencers(0, 2);
        long few = statementsOf(() -> memberInternalFacade.findMembers(null, 100));
        saveInfluencers(2, 8);

        long many = statementsOf(() -> memberInternalFacade.findMembers(null, 100));

        assertThat(many).isEqualTo(few);
    }

    private void saveInfluencers(int from, int count) {
        for (int i = from; i < from + count; i++) {
            User user = User.join("batch" + i + "@example.com", "batch" + i);
            user.changeRole(Role.INFLUENCER);
            user.changeContentCategories(List.of("GAME", "MUKBANG"));
            userRepository.saveAndFlush(user);
        }
    }

    private long statementsOf(Runnable action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }
}
