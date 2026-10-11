package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserJoinedEvent;

@ExtendWith(MockitoExtension.class)
class MemberTestAccountUseCaseTest {

    @Mock
    UserRepository userRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberTestAccountUseCase useCase;

    @Test
    @DisplayName("테스트 계정이 없으면 그 역할로 만들고 가입 이벤트에도 그 역할을 담는다")
    void findOrCreate_new() {
        given(userRepository.findByEmail("test-influencer@hapbang.local")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(returnsFirstArg());

        User user = useCase.findOrCreate(Role.INFLUENCER);

        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getNickname()).isEqualTo("테스트인플루언서");

        ArgumentCaptor<UserJoinedEvent> event = ArgumentCaptor.forClass(UserJoinedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().role()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("테스트 계정이 있으면 그 계정을 반환하고 새로 만들지 않는다")
    void findOrCreate_existing() {
        User existing = User.join("test-admin@hapbang.local", "테스트관리자");
        existing.changeRole(Role.ADMIN);
        given(userRepository.findByEmail("test-admin@hapbang.local")).willReturn(Optional.of(existing));

        User user = useCase.findOrCreate(Role.ADMIN);

        assertThat(user).isSameAs(existing);
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
