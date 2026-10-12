package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @DisplayName("테스트 계정이 없으면 역할마다 2개씩 만들고, 가입 이벤트에 그 역할을 담는다")
    void createAll_new() {
        given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());

        useCase.createAll();

        int total = Role.values().length * MemberTestAccountUseCase.ACCOUNTS_PER_ROLE;
        ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(total)).save(users.capture());
        assertThat(users.getAllValues())
                .extracting(User::getEmail)
                .contains("test-influencer@hapbang.local", "test-influencer-2@hapbang.local", "test-admin@hapbang.local");
        assertThat(users.getAllValues())
                .filteredOn(user -> user.getEmail().equals("test-influencer-2@hapbang.local"))
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
                    assertThat(user.getNickname()).isEqualTo("테스트인플루언서2");
                });

        ArgumentCaptor<UserJoinedEvent> events = ArgumentCaptor.forClass(UserJoinedEvent.class);
        verify(eventPublisher, times(total)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(event -> event.user().role()).contains(Role.ADMIN);
    }

    @Test
    @DisplayName("테스트 계정이 이미 있으면 새로 만들지 않는다")
    void createAll_existing() {
        given(userRepository.findByEmail(anyString()))
                .willReturn(Optional.of(User.join("test@hapbang.local", "테스트")));

        useCase.createAll();

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("역할과 번호로 테스트 계정 이메일을 찾는다")
    void find() {
        User user = User.join("test-influencer-2@hapbang.local", "테스트인플루언서2");
        given(userRepository.findByEmail("test-influencer-2@hapbang.local")).willReturn(Optional.of(user));

        assertThat(useCase.find(Role.INFLUENCER, 2)).contains(user);
    }
}
