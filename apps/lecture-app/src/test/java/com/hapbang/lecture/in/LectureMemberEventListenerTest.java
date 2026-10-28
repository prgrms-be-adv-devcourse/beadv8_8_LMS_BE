package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 회원 이벤트가 커밋되면 강의 회원 복제본에 반영되는지 검증한다. 리스너는 커밋 후 비동기로 실행되므로 기다려서 확인한다.
 */
@IntegrationTest
class LectureMemberEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Long USER_ID = 1L;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Test
    void 회원_가입_이벤트를_받으면_강의_회원으로_복제한다() {
        publishJoinedInTransaction(new UserDto(USER_ID, "판매자", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("판매자");
                    assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                    assertThat(replica.getStatus()).isEqualTo(UserStatus.ACTIVE);
                }));
    }

    @Test
    void 회원_정보_변경_이벤트를_받으면_강의_회원_정보를_갱신한다() {
        publishJoinedInTransaction(new UserDto(USER_ID, "구매자", Role.USER, UserStatus.ACTIVE));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID)).isPresent());

        publishModifiedInTransaction(new UserDto(USER_ID, "판매자", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID))
                .hasValueSatisfying(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("판매자");
                    assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                }));
    }

    @Test
    void 강의_회원이_없으면_변경_이벤트로_만들지_않는다() {
        publishModifiedInTransaction(new UserDto(USER_ID, "바뀐닉네임", Role.INFLUENCER, UserStatus.ACTIVE));

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID)).isEmpty());
    }

    @Test
    void 변경이_반영된_뒤_다시_처리된_가입_이벤트는_무시한다() {
        UserDto joined = new UserDto(USER_ID, "가입닉네임", Role.USER, UserStatus.ACTIVE);
        publishJoinedInTransaction(joined);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID)).isPresent());
        publishModifiedInTransaction(new UserDto(USER_ID, "바뀐닉네임", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID))
                .hasValueSatisfying(replica -> assertThat(replica.getNickname()).isEqualTo("바뀐닉네임")));

        publishJoinedInTransaction(joined);

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(lectureUserRepository.findById(USER_ID))
                        .hasValueSatisfying(replica -> {
                            assertThat(replica.getNickname()).isEqualTo("바뀐닉네임");
                            assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
                        }));
        assertThat(lectureUserRepository.count()).isEqualTo(1);
    }

    private void publishJoinedInTransaction(UserDto user) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserJoinedEvent(user)));
    }

    private void publishModifiedInTransaction(UserDto user) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserModifiedEvent(user)));
    }
}
