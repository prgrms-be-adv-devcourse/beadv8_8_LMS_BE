package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomMemberTest {

    @Test
    void 처음_입장하면_첫_메시지부터_볼_수_있다() {
        ChatRoomMember member = ChatRoomMember.join(directRoom(), HOST_ID, NOW);

        assertThat(member.isActive()).isTrue();
        assertThat(member.getVisibleFromSequence()).isEqualTo(1);
        assertThat(member.getParticipationVersion()).isEqualTo(1);
        assertThat(member.getLastReadSequence()).isZero();
    }

    @Test
    void 입장하면_이미_있는_메시지는_읽은_것으로_본다() {
        ChatRoom room = directRoom();
        room.issueSequence();
        room.issueSequence();

        ChatRoomMember member = ChatRoomMember.join(room, HOST_ID, NOW);

        assertThat(member.getLastReadSequence()).isEqualTo(2);
    }

    @Test
    void 나가면_퇴장_시각이_기록된다() {
        ChatRoomMember member = ChatRoomMember.join(directRoom(), HOST_ID, NOW);

        member.leave(NOW.plusHours(1));

        assertThat(member.isActive()).isFalse();
        assertThat(member.getLeftAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void 이미_나간_방은_다시_나갈_수_없다() {
        ChatRoomMember member = ChatRoomMember.join(directRoom(), HOST_ID, NOW);
        member.leave(NOW.plusHours(1));

        assertThatThrownBy(() -> member.leave(NOW.plusHours(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 나간 참여자는 다시 나갈 수 없습니다.");
    }

    @Test
    void 재입장하면_재입장_이전_메시지를_볼_수_없고_참여_세대가_바뀐다() {
        ChatRoom room = directRoom();
        ChatRoomMember member = ChatRoomMember.join(room, GUEST_ID, NOW);
        room.issueSequence();
        room.issueSequence();
        member.leave(NOW.plusHours(1));
        room.issueSequence();

        member.rejoin(NOW.plusHours(2));

        assertThat(member.isActive()).isTrue();
        assertThat(member.getJoinedAt()).isEqualTo(NOW.plusHours(2));
        assertThat(member.getVisibleFromSequence()).isEqualTo(4);
        assertThat(member.getParticipationVersion()).isEqualTo(2);
        assertThat(member.getLastReadSequence()).isEqualTo(3);
        assertThat(member.canView(3)).isFalse();
        assertThat(member.canView(4)).isTrue();
    }

    @Test
    void 참여_중인_회원의_재입장은_아무것도_바꾸지_않는다() {
        ChatRoom room = directRoom();
        ChatRoomMember member = ChatRoomMember.join(room, HOST_ID, NOW);
        room.issueSequence();

        member.rejoin(NOW.plusHours(1));

        assertThat(member.getVisibleFromSequence()).isEqualTo(1);
        assertThat(member.getParticipationVersion()).isEqualTo(1);
    }

    @Test
    void 처음_입장하면_수신_확인_순번은_미확인이다() {
        ChatRoom room = directRoom();
        room.issueSequence();

        ChatRoomMember member = ChatRoomMember.join(room, HOST_ID, NOW);

        assertThat(member.getLastReceivedSequence()).isNull();
    }

    @Test
    void 재입장하면_수신_확인_순번을_참여_세대_변경과_함께_비운다() {
        ChatRoom room = directRoom();
        ChatRoomMember member = ChatRoomMember.join(room, GUEST_ID, NOW);
        room.issueSequence();
        room.issueSequence();
        // 수신 확인 순번은 Repository의 조건부 갱신으로만 올라가므로 테스트에서는 필드를 직접 채운다
        ReflectionTestUtils.setField(member, "lastReceivedSequence", 2L);
        member.leave(NOW.plusHours(1));

        member.rejoin(NOW.plusHours(2));

        assertThat(member.getParticipationVersion()).isEqualTo(2);
        assertThat(member.getLastReceivedSequence()).isNull();
        assertThat(member.getLastReadSequence()).isEqualTo(2);
    }

    @Test
    void 참여_중인_회원의_재입장은_수신_확인_순번을_바꾸지_않는다() {
        ChatRoom room = directRoom();
        ChatRoomMember member = ChatRoomMember.join(room, HOST_ID, NOW);
        room.issueSequence();
        ReflectionTestUtils.setField(member, "lastReceivedSequence", 1L);

        member.rejoin(NOW.plusHours(1));

        assertThat(member.getLastReceivedSequence()).isEqualTo(1L);
    }

    @Test
    void 참여_세대를_비교한다() {
        ChatRoomMember member = ChatRoomMember.join(directRoom(), HOST_ID, NOW);
        member.leave(NOW);
        member.rejoin(NOW);

        assertThat(member.isCurrentParticipation(2)).isTrue();
        assertThat(member.isCurrentParticipation(1)).isFalse();
        assertThat(member.isCurrentParticipation(null)).isTrue();
    }
}
