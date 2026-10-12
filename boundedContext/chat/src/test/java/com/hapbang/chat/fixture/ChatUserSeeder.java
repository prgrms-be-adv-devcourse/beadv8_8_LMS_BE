package com.hapbang.chat.fixture;

import static com.hapbang.chat.fixture.ChatFixture.*;

import com.hapbang.chat.app.ChatUserService;
import com.hapbang.shared.member.domain.Role;

/**
 * 통합·E2E 테스트에서 회원 이벤트 대신 ChatUser를 복제해 둔다.
 */
public final class ChatUserSeeder {

    private ChatUserSeeder() {
    }

    public static void seedDefaultUsers(ChatUserService chatUserService) {
        chatUserService.createIfAbsent(activeUser(HOST_ID, "호스트", Role.INFLUENCER));
        chatUserService.createIfAbsent(activeUser(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER));
        chatUserService.createIfAbsent(activeUser(OTHER_INFLUENCER_ID, "다른인플루언서", Role.INFLUENCER));
        chatUserService.createIfAbsent(activeUser(USER_ID, "일반유저", Role.USER));
        chatUserService.createIfAbsent(activeUser(ADMIN_ID, "관리자", Role.ADMIN));
    }
}
