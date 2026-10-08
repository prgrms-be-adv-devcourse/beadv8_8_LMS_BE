package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChatUserTest {

    @Test
    void 일반_유저는_채팅할_수_없다() {
        ChatUser user = chatUser(USER_ID, UserRole.USER);

        assertThat(user.canChat()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = { "INFLUENCER", "VERIFIED_INFLUENCER", "ADMIN"})
    void 인플루언서_이상과_관리자는_채팅할_수_있다(UserRole role) {
        ChatUser user = chatUser(HOST_ID, role);

        assertThat(user.canChat()).isTrue();
    }

    @Test
    void 탈퇴하면_닉네임이_탈퇴한_회원으로_바뀌고_채팅할_수_없다() {
        ChatUser user = chatUser(HOST_ID, UserRole.INFLUENCER);

        user.withdraw(NOW.plusDays(30));

        assertThat(user.getNickname()).isEqualTo(ChatUser.WITHDRAWN_NICKNAME);
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.canChat()).isFalse();
    }

    @Test
    void 관리자_여부를_판단한다() {
        assertThat(chatUser(ADMIN_ID, UserRole.ADMIN).isAdmin()).isTrue();
        assertThat(chatUser(HOST_ID, UserRole.VERIFIED_INFLUENCER).isAdmin()).isFalse();
    }

    @Test
    void 프로필을_변경한다() {
        ChatUser user = chatUser(USER_ID, UserRole.USER);

        user.changeProfile("새닉네임", UserRole.INFLUENCER, NOW.plusDays(1));

        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getRole()).isEqualTo(UserRole.INFLUENCER);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 닉네임이_비어_있으면_만들_수_없다() {
        assertThatThrownBy(() -> ChatUser.create(HOST_ID, " ", UserRole.INFLUENCER, NOW))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);
    }
}
