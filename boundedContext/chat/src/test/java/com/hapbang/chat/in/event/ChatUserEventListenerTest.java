package com.hapbang.chat.in.event;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 회원 가입 이벤트가 커밋되면 채팅 회원으로 복제되는지 검증한다. 리스너는 커밋 후 비동기로 실행되므로 기다려서 확인한다.
 */
@IntegrationTest
class ChatUserEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Test
    void 회원_가입_이벤트를_받으면_채팅_회원으로_복제한다() {
        publishInTransaction(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(HOST_ID))
                .hasValueSatisfying(chatUser -> {
                    assertThat(chatUser.getNickname()).isEqualTo("호스트");
                    assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
                    assertThat(chatUser.canChat()).isTrue();
                }));
    }

    @Test
    void 같은_이벤트를_두_번_받아도_한_명만_복제한다() {
        UserDto user = activeUser(GUEST_ID, "게스트", Role.USER);

        publishInTransaction(user);
        publishInTransaction(user);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(GUEST_ID)).isPresent());
        await().during(Duration.ofMillis(300)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(chatUserRepository.count()).isEqualTo(1));
        assertThat(chatUserRepository.findById(GUEST_ID)).get()
                .extracting(ChatUser::canChat).isEqualTo(false);
    }

    @Test
    void 커밋되지_않은_가입_이벤트는_복제하지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserJoinedEvent(activeUser(ADMIN_ID, "관리자", Role.ADMIN)));
            status.setRollbackOnly();
        });

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(chatUserRepository.findById(ADMIN_ID)).isEmpty());
    }

    @Test
    void 회원_정보_변경_이벤트를_받으면_닉네임_역할_상태를_갱신한다() {
        publishInTransaction(activeUser(HOST_ID, "호스트", Role.USER));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(HOST_ID)).isPresent());

        publishModifiedInTransaction(userDto(HOST_ID, "새닉네임", Role.INFLUENCER, UserStatus.ACTIVE));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(HOST_ID))
                .hasValueSatisfying(chatUser -> {
                    assertThat(chatUser.getNickname()).isEqualTo("새닉네임");
                    assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
                    assertThat(chatUser.canChat()).isTrue();
                }));
    }

    @Test
    void 채팅_회원이_없으면_변경_이벤트로_생성한다() {
        publishModifiedInTransaction(activeUser(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(GUEST_ID))
                .hasValueSatisfying(chatUser -> assertThat(chatUser.getNickname()).isEqualTo("게스트")));
    }

    @Test
    void 변경이_반영된_뒤_늦게_온_가입_이벤트는_무시한다() {
        publishModifiedInTransaction(userDto(HOST_ID, "바뀐닉네임", Role.INFLUENCER, UserStatus.WITHDRAWN));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatUserRepository.findById(HOST_ID)).isPresent());

        publishInTransaction(activeUser(HOST_ID, "가입닉네임", Role.INFLUENCER));

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(chatUserRepository.findById(HOST_ID))
                        .hasValueSatisfying(chatUser -> {
                            assertThat(chatUser.getNickname()).isEqualTo("바뀐닉네임");
                            assertThat(chatUser.isWithdrawn()).isTrue();
                        }));
    }

    private void publishInTransaction(UserDto user) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserJoinedEvent(user)));
    }

    private void publishModifiedInTransaction(UserDto user) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserModifiedEvent(user)));
    }
}
