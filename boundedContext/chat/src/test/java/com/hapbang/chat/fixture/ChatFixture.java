package com.hapbang.chat.fixture;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.UserRole;

/**
 * 채팅 테스트 공용 데이터.
 */
public final class ChatFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

    public static final Long HOST_ID = 1L;
    public static final Long GUEST_ID = 2L;
    public static final Long OTHER_INFLUENCER_ID = 3L;
    public static final Long USER_ID = 4L;
    public static final Long ADMIN_ID = 5L;
    public static final Long UNKNOWN_ID = 999L;

    private ChatFixture() {
    }

    public static ChatUser chatUser(Long userId, UserRole role) {
        return ChatUser.create(userId, "회원" + userId, role, NOW);
    }

    public static ChatRoom directRoom() {
        return ChatRoom.createDirect(HOST_ID, GUEST_ID, NOW);
    }
}
