package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatUserServiceTest {

    @Autowired
    private ChatUserService chatUserService;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Test
    void 처음_받은_회원_정보는_새로_복제한다() {
        chatUserService.createOrUpdate(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("호스트");
        assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(chatUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(chatUser.getCreatedAt()).isNotNull();
    }

    @Test
    void 이미_있는_회원_정보는_갱신한다() {
        chatUserService.createOrUpdate(activeUser(HOST_ID, "유저", Role.USER));

        chatUserService.createOrUpdate(activeUser(HOST_ID, "인플루언서", Role.INFLUENCER));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("인플루언서");
        assertThat(chatUser.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(chatUserRepository.count()).isEqualTo(1);
    }

    @Test
    void 가입_정보는_채팅_회원이_없을_때만_생성한다() {
        chatUserService.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        assertThat(chatUserRepository.findById(HOST_ID)).get()
                .extracting(ChatUser::getNickname).isEqualTo("호스트");
    }

    @Test
    void 이미_있는_채팅_회원에게_온_가입_정보는_무시한다() {
        chatUserService.createOrUpdate(activeUser(HOST_ID, "바뀐닉네임", Role.VERIFIED_INFLUENCER));

        chatUserService.createIfAbsent(activeUser(HOST_ID, "가입닉네임", Role.USER));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.getNickname()).isEqualTo("바뀐닉네임");
        assertThat(chatUser.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
    }

    @Test
    void 탈퇴한_회원은_탈퇴한_회원으로_표시된다() {
        chatUserService.createOrUpdate(activeUser(HOST_ID, "호스트", Role.INFLUENCER));

        chatUserService.createOrUpdate(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.WITHDRAWN));

        ChatUser chatUser = chatUserRepository.findById(HOST_ID).orElseThrow();
        assertThat(chatUser.isWithdrawn()).isTrue();
        assertThat(chatUser.getDisplayNickname()).isEqualTo(ChatUser.WITHDRAWN_NICKNAME);
    }
}
