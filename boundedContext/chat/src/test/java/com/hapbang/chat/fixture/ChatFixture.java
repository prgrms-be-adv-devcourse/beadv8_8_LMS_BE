package com.hapbang.chat.fixture;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

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

    public static UserDto userDto(Long userId, String nickname, Role role, UserStatus status) {
        return new UserDto(userId, nickname, role, status);
    }

    public static UserDto activeUser(Long userId, String nickname, Role role) {
        return userDto(userId, nickname, role, UserStatus.ACTIVE);
    }

    public static ChatUser chatUser(Long userId, Role role) {
        return ChatUser.create(activeUser(userId, "회원" + userId, role));
    }

    public static ChatRoom directRoom() {
        return ChatRoom.createDirect(HOST_ID, GUEST_ID, NOW);
    }
}
