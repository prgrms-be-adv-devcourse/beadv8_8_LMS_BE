package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatRoomMemberTest {

    @Test
    void 입장하면_참여_중이다() {
        ChatRoomMember member = ChatRoomMember.join(directRoom(), HOST_ID, NOW);

        assertThat(member.isActive()).isTrue();
        assertThat(member.getJoinedAt()).isEqualTo(NOW);
        assertThat(member.getLeftAt()).isNull();
    }
}
