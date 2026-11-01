package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.dto.ChatUserSyncResult.Outcome;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatUserUseCaseTest {

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Test
    void 가입_정보로_채팅_회원을_생성한다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("호스트");
        assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(chatUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(chatUser.getCreatedAt()).isNotNull();
    }

    @Test
    void 이미_있는_채팅_회원에게_온_가입_정보는_무시한다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "가입닉네임", Role.USER));
        chatUserUseCase.update(activeUser(HOST_ID, "바뀐닉네임", Role.VERIFIED_INFLUENCER));

        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "가입닉네임", Role.USER));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("바뀐닉네임");
        assertThat(chatUser.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
        assertThat(chatUserRepository.count()).isEqualTo(1);
    }

    @Test
    void 변경_정보로_닉네임_역할_상태를_갱신한다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "유저", Role.USER));

        chatUserUseCase.update(userDto(HOST_ID, "인플루언서", Role.INFLUENCER, UserStatus.SUSPENDED));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("인플루언서");
        assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void 채팅_회원이_없으면_변경_정보로_생성하지_않고_실패한다() {
        assertThatThrownBy(() -> chatUserUseCase.update(activeUser(HOST_ID, "바뀐닉네임", Role.INFLUENCER)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);

        assertThat(chatUserRepository.findById(HOST_ID)).isEmpty();
    }

    @Test
    void 복제할_수_없는_회원_정보는_도메인을_부르기_전에_업무_오류로_거절한다() {
        assertThatThrownBy(() -> chatUserUseCase.createIfAbsent(activeUser(HOST_ID, " ", Role.INFLUENCER)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, null)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);

        assertThat(chatUserRepository.findById(HOST_ID)).isEmpty();
    }

    @Test
    void 탈퇴한_회원은_탈퇴한_회원으로_표시된다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.WITHDRAWN));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.isWithdrawn()).isTrue();
        assertThat(chatUser.getDisplayNickname()).isEqualTo(ChatUser.WITHDRAWN_NICKNAME);
    }

    @Test
    void 프로필만_수정하면_갱신했고_계속_채팅할_수_있다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        ChatUserSyncResult result = chatUserUseCase.update(activeUser(HOST_ID, "새닉네임", Role.INFLUENCER));

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.canChat()).isTrue();
    }

    @Test
    void 정지_탈퇴_강등을_반영하면_갱신_뒤_채팅할_수_없다() {
        chatUserUseCase.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));
        chatUserUseCase.createIfAbsent(activeUser(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER));
        chatUserUseCase.createIfAbsent(activeUser(ADMIN_ID, "관리자", Role.ADMIN));

        assertThat(chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED)))
                .extracting(ChatUserSyncResult::outcome, ChatUserSyncResult::canChat)
                .containsExactly(Outcome.UPDATED, false);
        assertThat(chatUserUseCase.update(userDto(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN)))
                .extracting(ChatUserSyncResult::outcome, ChatUserSyncResult::canChat)
                .containsExactly(Outcome.UPDATED, false);
        assertThat(chatUserUseCase.update(activeUser(ADMIN_ID, "관리자", Role.USER)))
                .extracting(ChatUserSyncResult::outcome, ChatUserSyncResult::canChat)
                .containsExactly(Outcome.UPDATED, false);
    }

    @Test
    void 이미_반영한_버전보다_새롭지_않은_변경은_건너뛰고_현재_버전을_돌려준다() {
        chatUserUseCase.createIfAbsent(new UserDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.ACTIVE, 3L));

        ChatUserSyncResult result = chatUserUseCase.update(
                new UserDto(HOST_ID, "옛닉네임", Role.USER, UserStatus.ACTIVE, 2L));

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED_STALE);
        assertThat(result.memberVersion()).isEqualTo(3L);
        assertThat(result.canChat()).isTrue();
        assertThat(chatUserRepository.findById(HOST_ID)).get().extracting(ChatUser::getNickname).isEqualTo("호스트");
    }

    @Test
    void 이미_있는_회원의_가입_정보는_건너뛴다고_돌려준다() {
        chatUserUseCase.createIfAbsent(activeUser(USER_ID, "유저", Role.USER));

        assertThat(chatUserUseCase.createIfAbsent(activeUser(USER_ID, "유저", Role.USER)).outcome())
                .isEqualTo(Outcome.SKIPPED_EXISTING);
    }
}
