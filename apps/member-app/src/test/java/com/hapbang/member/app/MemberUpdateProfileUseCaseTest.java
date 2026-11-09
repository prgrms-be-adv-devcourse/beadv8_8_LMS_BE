package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

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
import com.hapbang.member.out.ContentCategoryRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserModifiedEvent;

@ExtendWith(MockitoExtension.class)
class MemberUpdateProfileUseCaseTest {

    @Mock
    MemberSupport memberSupport;

    @Mock
    ContentCategoryRepository contentCategoryRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberUpdateProfileUseCase useCase;

    @Test
    @DisplayName("닉네임을 바꾸고 새 닉네임을 담은 변경 이벤트를 발행한다")
    void update_nickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        User result = useCase.update(1L, "새 닉네임", null, null);

        assertThat(result.getNickname()).isEqualTo("새 닉네임");
        ArgumentCaptor<UserModifiedEvent> event = ArgumentCaptor.forClass(UserModifiedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().nickname()).isEqualTo("새 닉네임");
    }

    @Test
    @DisplayName("다른 회원이 쓰는 닉네임이면 DUPLICATE_NICKNAME이고 바꾸지 않으며 이벤트도 없다 (#646)")
    void update_duplicateNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);
        willThrow(new MemberException(MemberErrorCode.DUPLICATE_NICKNAME))
                .given(memberSupport).checkNicknameAvailable("tester2", 1L);

        assertThatThrownBy(() -> useCase.update(1L, " tester2 ", null, null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);
        assertThat(user.getNickname()).isEqualTo("tester1");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("모두 없으면(null) 바꾸지 않고 이벤트도 발행하지 않는다")
    void update_withoutNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        User result = useCase.update(1L, null, null, null);

        assertThat(result.getNickname()).isEqualTo("tester1");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임이면 예외이고 이벤트를 발행하지 않는다")
    void update_invalidNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        assertThatThrownBy(() -> useCase.update(1L, "민", null, null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_NICKNAME_LENGTH);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("없는 회원이면 MEMBER_NOT_FOUND")
    void update_memberNotFound() {
        given(memberSupport.findUser(99L)).willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> useCase.update(99L, "새 닉네임", null, null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("지금과 같은 닉네임이면 바꾼 것이 없어 이벤트를 발행하지 않는다")
    void update_sameNickname() {
        User user = User.join("tester1@example.com", "tester1");
        given(memberSupport.findUser(1L)).willReturn(user);

        useCase.update(1L, " tester1 ", null, null);

        verify(memberSupport, never()).checkNicknameAvailable(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("인플루언서가 닉네임·분야·자기소개를 한 번에 바꾸면 이벤트를 한 번 발행하고, 새 값이 실린다 (#711)")
    void update_all() {
        User user = influencer();
        given(memberSupport.findUser(1L)).willReturn(user);
        given(contentCategoryRepository.countByCodeInAndActiveTrue(List.of("MAPLESTORY", "GAME"))).willReturn(2L);

        useCase.update(1L, "새 닉네임", List.of("MAPLESTORY", "GAME"), " 메이플 위주 ");

        ArgumentCaptor<UserModifiedEvent> event = ArgumentCaptor.forClass(UserModifiedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user().nickname()).isEqualTo("새 닉네임");
        assertThat(event.getValue().user().contentCategories()).containsExactly("MAPLESTORY", "GAME");
        assertThat(event.getValue().user().bio()).isEqualTo("메이플 위주");
        verify(memberSupport).flush();
    }

    @Test
    @DisplayName("지금과 같은 분야·자기소개를 보내면 바꾼 것이 없어 이벤트를 발행하지 않는다 (#711)")
    void update_sameContentProfile() {
        User user = influencer();
        user.changeContentCategories(List.of("GAME"));
        user.changeBio("메이플 위주");
        given(memberSupport.findUser(1L)).willReturn(user);

        useCase.update(1L, null, List.of("GAME"), "메이플 위주");

        verify(contentCategoryRepository, never()).countByCodeInAndActiveTrue(anyCollection());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("유저·관리자가 비어 있지 않은 분야나 자기소개를 보내면 403이고 닉네임도 바꾸지 않는다 (#711)")
    void update_notInfluencer() {
        User user = User.join("tester1@example.com", "tester1");
        User admin = User.join("admin@example.com", "admin");
        admin.changeRole(Role.ADMIN);
        given(memberSupport.findUser(1L)).willReturn(user);
        given(memberSupport.findUser(2L)).willReturn(admin);

        assertThatThrownBy(() -> useCase.update(1L, "새 닉네임", List.of("GAME"), null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INFLUENCER_PROFILE_NOT_ALLOWED);
        assertThatThrownBy(() -> useCase.update(2L, null, null, "소개"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INFLUENCER_PROFILE_NOT_ALLOWED);
        assertThat(user.getNickname()).isEqualTo("tester1");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("유저도 빈 분야·빈 자기소개로 남은 값을 지울 수 있다 (강등된 회원, #711)")
    void update_clearByUser() {
        User user = influencer();
        user.changeContentCategories(List.of("GAME"));
        user.changeBio("메이플 위주");
        user.changeRole(Role.USER);
        given(memberSupport.findUser(1L)).willReturn(user);

        useCase.update(1L, null, List.of(), "  ");

        assertThat(user.getContentCategories()).isEmpty();
        assertThat(user.getBio()).isNull();
        verifyNoInteractions(contentCategoryRepository);
        verify(eventPublisher).publishEvent(any(UserModifiedEvent.class));
    }

    @Test
    @DisplayName("새로 추가하는 분야가 목록에 없거나 꺼져 있으면 INVALID_CONTENT_CATEGORY이고 아무것도 바꾸지 않는다 (#711)")
    void update_unselectableCategory() {
        User user = influencer();
        given(memberSupport.findUser(1L)).willReturn(user);
        given(contentCategoryRepository.countByCodeInAndActiveTrue(List.of("GAME", "NO_SUCH"))).willReturn(1L);

        assertThatThrownBy(() -> useCase.update(1L, "새 닉네임", List.of("GAME", "NO_SUCH"), "소개"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY);
        assertThat(user.getNickname()).isEqualTo("tester1");
        assertThat(user.getContentCategories()).isEmpty();
        assertThat(user.getBio()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 고른 분야는 목록에서 꺼졌어도 다시 보낼 수 있고, 새로 추가하는 분야만 확인한다 (Q50)")
    void update_keepsAlreadySelected() {
        User user = influencer();
        user.changeContentCategories(List.of("TURNED_OFF"));
        given(memberSupport.findUser(1L)).willReturn(user);
        given(contentCategoryRepository.countByCodeInAndActiveTrue(List.of("GAME"))).willReturn(1L);

        useCase.update(1L, null, List.of("TURNED_OFF", "GAME"), null);

        assertThat(user.getContentCategories()).containsExactly("TURNED_OFF", "GAME");
        verify(contentCategoryRepository).countByCodeInAndActiveTrue(List.of("GAME"));
    }

    @Test
    @DisplayName("분야가 6개거나 자기소개가 101자면 400이고 아무것도 바꾸지 않는다 (#711)")
    void update_invalidContentProfile() {
        User user = influencer();
        given(memberSupport.findUser(1L)).willReturn(user);

        assertThatThrownBy(() -> useCase.update(1L, null,
                List.of("GAME", "MUKBANG", "BEAUTY", "MUSIC", "SPORTS", "TRAVEL"), null))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CONTENT_CATEGORY_COUNT);
        assertThatThrownBy(() -> useCase.update(1L, null, null, "가".repeat(101)))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_BIO_LENGTH);
        verify(eventPublisher, never()).publishEvent(any());
    }

    private static User influencer() {
        User user = User.join("tester1@example.com", "tester1");
        user.changeRole(Role.INFLUENCER);
        return user;
    }
}
