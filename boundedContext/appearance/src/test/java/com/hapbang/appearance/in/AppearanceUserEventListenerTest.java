package com.hapbang.appearance.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 회원 모듈이 가입 이벤트를 발행한 것처럼 만들고, 출연 회원이 생기는지 확인한다.
 * 리스너는 발행한 트랜잭션이 커밋된 뒤에 실행되므로 트랜잭션 안에서 발행하고, 결과가 생길 때까지 기다린다.
 */
@IntegrationTest
class AppearanceUserEventListenerTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Test
    @DisplayName("회원 가입 이벤트를 받으면 출연 회원을 만든다")
    void createsAppearanceUserOnUserJoined() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserJoinedEvent(AppearanceUserFixture.influencer(5L))));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(appearanceUserRepository.findById(5L)).isPresent());
    }

    @Test
    @DisplayName("발행한 트랜잭션이 롤백되면 출연 회원을 만들지 않는다")
    void doesNothingWhenPublisherRollsBack() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserJoinedEvent(AppearanceUserFixture.influencer(6L)));
            status.setRollbackOnly();
        });

        assertThat(appearanceUserRepository.findById(6L)).isEmpty();
    }
}
