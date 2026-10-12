package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatValidationServiceTest {

    @Autowired
    private ChatValidationService chatValidationService;

    @Autowired
    private ChatUserService chatUserService;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
    }

    @Test
    void 인플루언서와_관리자는_채팅할_수_있다() {
        assertThatCode(() -> {
            chatValidationService.validateCanChat(HOST_ID);
            chatValidationService.validateCanChat(GUEST_ID);
            chatValidationService.validateCanChat(ADMIN_ID);
        }).doesNotThrowAnyException();
        assertThat(chatValidationService.getChattableUser(HOST_ID).getNickname()).isEqualTo("호스트");
    }

    @Test
    void 일반_유저와_탈퇴한_회원은_채팅할_수_없다() {
        chatUserService.withdraw(GUEST_ID);

        for (Long userId : new Long[] {USER_ID, GUEST_ID}) {
            assertThatThrownBy(() -> chatValidationService.validateCanChat(userId))
                    .isInstanceOf(ChatException.class)
                    .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
        }
    }

    @Test
    void 복제된_회원_정보가_없으면_찾을_수_없다() {
        assertThatThrownBy(() -> chatValidationService.validateCanChat(UNKNOWN_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);
    }

    @Test
    void 관리자만_관리자_검증을_통과한다() {
        assertThatCode(() -> chatValidationService.validateAdmin(ADMIN_ID)).doesNotThrowAnyException();
        assertThatThrownBy(() -> chatValidationService.validateAdmin(HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.ADMIN_ONLY);
    }
}
