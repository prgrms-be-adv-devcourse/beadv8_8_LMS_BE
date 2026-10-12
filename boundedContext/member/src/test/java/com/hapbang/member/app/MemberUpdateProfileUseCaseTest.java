package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.event.UserModifiedEvent;

@ExtendWith(MockitoExtension.class)
class MemberUpdateProfileUseCaseTest {

    @Mock
    MemberSupport memberSupport;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberUpdateProfileUseCase useCase;

    @Test
    @DisplayName("닉네임을 바꾸고 새 닉네임을 담은 변경 이벤트를 발행한다")
    void update_nickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        User result = useCase.update(1L, "새 닉네임");

        assertThat(result.getNickname()).isEqualTo("새 닉네임");
        ArgumentCaptor<UserModifiedEvent> event = ArgumentCaptor.forClass(UserModifiedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().nickname()).isEqualTo("새 닉네임");
    }

    @Test
    @DisplayName("닉네임이 없으면(null) 바꾸지 않고 이벤트도 발행하지 않는다")
    void update_withoutNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        User result = useCase.update(1L, null);

        assertThat(result.getNickname()).isEqualTo("tester1");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임이면 예외이고 이벤트를 발행하지 않는다")
    void update_invalidNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        assertThatThrownBy(() -> useCase.update(1L, "민"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_LENGTH);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("없는 회원이면 MEMBER_NOT_FOUND")
    void update_memberNotFound() {
        given(memberSupport.findUser(99L)).willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> useCase.update(99L, "새 닉네임"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }
}
