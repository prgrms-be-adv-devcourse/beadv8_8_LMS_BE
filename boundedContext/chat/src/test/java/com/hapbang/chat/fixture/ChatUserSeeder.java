package com.hapbang.chat.fixture;

import static com.hapbang.chat.fixture.ChatFixture.*;

import com.hapbang.chat.app.ChatUserService;
import com.hapbang.chat.domain.UserRole;

/**
 * 통합·E2E 테스트에서 회원 이벤트 대신 ChatUser를 복제해 둔다.
 */
public final class ChatUserSeeder {

    private ChatUserSeeder() {
    }

    public static void seedDefaultUsers(ChatUserService chatUserService) {
        chatUserService.sync(HOST_ID, "호스트", UserRole.INFLUENCER);
        chatUserService.sync(GUEST_ID, "게스트", UserRole.VERIFIED_INFLUENCER);
        chatUserService.sync(OTHER_INFLUENCER_ID, "다른인플루언서", UserRole.INFLUENCER);
        chatUserService.sync(USER_ID, "일반유저", UserRole.USER);
        chatUserService.sync(ADMIN_ID, "관리자", UserRole.ADMIN);
    }
}
