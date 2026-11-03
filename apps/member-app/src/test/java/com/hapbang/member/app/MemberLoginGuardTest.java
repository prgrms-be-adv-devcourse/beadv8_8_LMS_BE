package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.WithdrawnRestorableException;
import com.hapbang.member.out.RestoreTicketStore;
import com.hapbang.shared.member.domain.UserStatus;

class MemberLoginGuardTest {

    private ObjectProvider<MemberWithdrawalUseCase> withdrawal;
    private RestoreTicketStore restoreTicketStore;
    private MemberLoginGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        withdrawal = mock(ObjectProvider.class);
        restoreTicketStore = mock(RestoreTicketStore.class);
        guard = new MemberLoginGuard(withdrawal, restoreTicketStore);
    }

    @Test
    @DisplayName("정상 회원은 그대로 로그인한다")
    void active() {
        assertThatCode(() -> guard.check(User.join("tester1@example.com", "tester1"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("복구 기한 안의 탈퇴 회원이면 복구 티켓을 만들고 WITHDRAWN_RESTORABLE에 티켓과 기한을 싣는다")
    void restorable() {
        enableWithdrawal();
        User user = withdrawnAt(LocalDateTime.now().minusDays(3));
        given(restoreTicketStore.issue(user.getId())).willReturn("ticket-1");

        assertThatThrownBy(() -> guard.check(user))
                .isInstanceOfSatisfying(WithdrawnRestorableException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.WITHDRAWN_RESTORABLE);
                    assertThat(e.getRestoreTicket()).isEqualTo("ticket-1");
                    assertThat(e.getRestorableUntil()).isEqualTo(user.getDeletedAt().plus(User.RESTORE_PERIOD));
                });
    }

    @Test
    @DisplayName("복구 기한이 지난 탈퇴 회원은 티켓 없이 LOGIN_NOT_ALLOWED")
    void expired() {
        enableWithdrawal();
        User user = withdrawnAt(LocalDateTime.now().minusDays(31));

        assertNotAllowed(user);
    }

    @Test
    @DisplayName("탈퇴 기능이 꺼져 있으면 복구하지 않고 LOGIN_NOT_ALLOWED")
    void withdrawalDisabled() {
        User user = withdrawnAt(LocalDateTime.now().minusDays(3));

        assertNotAllowed(user);
    }

    @Test
    @DisplayName("정지 회원은 LOGIN_NOT_ALLOWED")
    void suspended() {
        enableWithdrawal();
        User user = User.join("tester1@example.com", "tester1");
        ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);

        assertNotAllowed(user);
    }

    private void enableWithdrawal() {
        given(withdrawal.getIfAvailable()).willReturn(mock(MemberWithdrawalUseCase.class));
    }

    private static User withdrawnAt(LocalDateTime withdrawnAt) {
        User user = User.join("tester1@example.com", "tester1");
        ReflectionTestUtils.setField(user, "id", 1L);
        user.withdraw(withdrawnAt);
        return user;
    }

    private void assertNotAllowed(User user) {
        assertThatThrownBy(() -> guard.check(user))
                .isInstanceOf(MemberException.class)
                .isNotInstanceOf(WithdrawnRestorableException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_NOT_ALLOWED);
        verify(restoreTicketStore, never()).issue(any());
    }
}
