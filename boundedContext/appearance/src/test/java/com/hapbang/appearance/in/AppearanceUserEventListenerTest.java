package com.hapbang.appearance.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.AppearanceUserSupport;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 회원 모듈이 가입·변경 이벤트를 발행한 것처럼 만들고, 출연 회원이 생기는지 확인한다.
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

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

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

    @Test
    @DisplayName("회원 정보 변경 이벤트를 받으면 닉네임·역할·상태를 갱신한다")
    void updatesAppearanceUserOnUserModified() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(7L));
        UserDto modified = new UserDto(7L, "renamed7", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED);

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserModifiedEvent(modified)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AppearanceUser found = appearanceUserRepository.findById(7L).orElseThrow();
            assertThat(found.getNickname()).isEqualTo("renamed7");
            assertThat(found.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
            assertThat(found.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        });
        assertThat(appearanceUserRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("출연 회원이 아직 없으면 변경 이벤트로 만들지 않는다 (처리 실패로 남아 재시작 때 재처리)")
    void doesNotCreateOnUserModifiedWhenMissing() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new UserModifiedEvent(AppearanceUserFixture.influencer(8L))));

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(appearanceUserRepository.findById(8L)).isEmpty());
    }

    @Test
    @DisplayName("변경이 반영된 뒤 다시 처리된 가입 이벤트는 무시한다")
    void ignoresLateUserJoinedAfterModified() {
        UserDto joined = AppearanceUserFixture.user(10L, Role.INFLUENCER, UserStatus.ACTIVE);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserJoinedEvent(joined)));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(appearanceUserRepository.findById(10L)).isPresent());
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserModifiedEvent(
                new UserDto(10L, "renamed10", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE))));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                appearanceUserRepository.findById(10L).orElseThrow().getNickname()).isEqualTo("renamed10"));

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserJoinedEvent(joined)));

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AppearanceUser found = appearanceUserRepository.findById(10L).orElseThrow();
            assertThat(found.getNickname()).isEqualTo("renamed10");
            assertThat(found.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
        });
        assertThat(appearanceUserRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("변경 이벤트를 발행한 트랜잭션이 롤백되면 반영하지 않는다")
    void ignoresUserModifiedWhenPublisherRollsBack() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(9L));

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserModifiedEvent(
                    AppearanceUserFixture.user(9L, Role.INFLUENCER, UserStatus.WITHDRAWN)));
            status.setRollbackOnly();
        });

        assertThat(appearanceUserRepository.findById(9L).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
