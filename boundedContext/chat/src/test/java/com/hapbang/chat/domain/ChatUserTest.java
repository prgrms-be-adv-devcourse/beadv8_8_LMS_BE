package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

class ChatUserTest {

    @Test
    void 일반_유저는_채팅할_수_없다() {
        assertThat(chatUser(USER_ID, Role.USER).canChat()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INFLUENCER", "VERIFIED_INFLUENCER", "ADMIN"})
    void 인플루언서_이상과_관리자는_채팅할_수_있다(Role role) {
        assertThat(chatUser(HOST_ID, role).canChat()).isTrue();
    }

    @Test
    void 탈퇴한_회원은_채팅할_수_없고_탈퇴한_회원으로_표시된다() {
        ChatUser user = chatUser(HOST_ID, Role.INFLUENCER);

        user.syncFrom(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.WITHDRAWN));

        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.canChat()).isFalse();
        assertThat(user.getDisplayNickname()).isEqualTo(ChatUser.WITHDRAWN_NICKNAME);
    }

    @Test
    void 정지된_회원은_채팅할_수_없다() {
        ChatUser user = chatUser(HOST_ID, Role.INFLUENCER);

        user.syncFrom(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));

        assertThat(user.canChat()).isFalse();
        assertThat(user.getDisplayNickname()).isEqualTo("호스트");
    }

    @Test
    void 관리자_여부를_판단한다() {
        assertThat(chatUser(ADMIN_ID, Role.ADMIN).isAdmin()).isTrue();
        assertThat(chatUser(HOST_ID, Role.VERIFIED_INFLUENCER).isAdmin()).isFalse();
    }

    @Test
    void 원본_회원_변경_내용을_반영한다() {
        ChatUser user = chatUser(USER_ID, Role.USER);

        user.syncFrom(activeUser(USER_ID, "새닉네임", Role.INFLUENCER));

        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.canChat()).isTrue();
    }

    @Test
    void 다른_회원의_정보로는_갱신할_수_없다() {
        ChatUser user = chatUser(HOST_ID, Role.INFLUENCER);

        assertThatThrownBy(() -> user.syncFrom(activeUser(GUEST_ID, "게스트", Role.INFLUENCER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 닉네임이나_상태가_비어_있으면_만들_수_없다() {
        assertThatThrownBy(() -> ChatUser.create(activeUser(HOST_ID, " ", Role.INFLUENCER)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> ChatUser.create(userDto(HOST_ID, "호스트", Role.INFLUENCER, null)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);
    }
}
