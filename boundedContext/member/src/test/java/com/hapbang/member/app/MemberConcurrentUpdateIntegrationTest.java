package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;

/** 같은 회원을 동시에 수정: 먼저 커밋한 쪽만 반영되고, 늦게 저장한 쪽은 409로 실패한다 (@Version) */
@IntegrationTest
@RecordApplicationEvents
class MemberConcurrentUpdateIntegrationTest {

    @Autowired
    MemberSupport memberSupport;

    @Autowired
    MemberUpdateProfileUseCase memberUpdateProfileUseCase;

    @Autowired
    MemberProfileImageUseCase memberProfileImageUseCase;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("회원을 수정하면 버전이 1 오른다")
    void versionIncreases() {
        User user = userRepository.save(MemberFixture.user());
        assertThat(user.getVersion()).isZero();

        inNewTransaction(() -> memberUpdateProfileUseCase.update(user.getId(), "새닉네임"));

        assertThat(find(user).getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("닉네임을 고치는 사이 역할이 먼저 바뀌면 닉네임 수정은 409, 역할은 되돌아가지 않는다")
    void nicknameAfterRoleChange() {
        User user = userRepository.save(MemberFixture.user());

        assertConcurrentUpdate(() -> inNewTransaction(() -> {
            memberSupport.findUser(user.getId());          // 본인 수정이 먼저 읽음 (버전 0)
            changeRoleInAnotherTransaction(user.getId());  // 그 사이 관리자 역할 변경 커밋 (버전 1)
            memberUpdateProfileUseCase.update(user.getId(), "새닉네임");
        }));

        User saved = find(user);
        assertThat(saved.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(saved.getNickname()).isEqualTo(user.getNickname());
        // 실패한 닉네임 수정은 이벤트를 내지 않는다 (역할 변경 1건만)
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().role()).isEqualTo(Role.INFLUENCER));
    }

    @Test
    @DisplayName("역할을 바꾸는 사이 닉네임이 먼저 바뀌면 역할 변경은 409, 닉네임은 유지된다")
    void roleChangeAfterNickname() {
        User user = userRepository.save(MemberFixture.user());

        assertConcurrentUpdate(() -> inNewTransaction(() -> {
            User loaded = memberSupport.findUser(user.getId());
            inNewTransaction(() -> memberUpdateProfileUseCase.update(user.getId(), "새닉네임"));
            memberSupport.changeRole(loaded, Role.INFLUENCER);
        }));

        User saved = find(user);
        assertThat(saved.getNickname()).isEqualTo("새닉네임");
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().nickname()).isEqualTo("새닉네임"));
    }

    @Test
    @DisplayName("프로필 사진을 바꾸는 사이 역할이 먼저 바뀌면 사진 변경은 409, 역할은 유지된다")
    void profileImageAfterRoleChange() {
        User user = userRepository.save(MemberFixture.user());

        assertConcurrentUpdate(() -> inNewTransaction(() -> {
            memberSupport.findUser(user.getId());
            changeRoleInAnotherTransaction(user.getId());
            memberProfileImageUseCase.change(user.getId(), "members/profile/" + user.getId() + "/new.png");
        }));

        User saved = find(user);
        assertThat(saved.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(saved.getProfileImageKey()).isNull();
    }

    private void assertConcurrentUpdate(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_CONCURRENT_UPDATE);
    }

    /** 관리자 역할 변경을 별도 트랜잭션으로 커밋한다 (USER → INFLUENCER) */
    private void changeRoleInAnotherTransaction(Long userId) {
        inNewTransaction(() -> memberSupport.changeRole(memberSupport.findUser(userId), Role.INFLUENCER));
    }

    private void inNewTransaction(Runnable action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> action.run());
    }

    private User find(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
