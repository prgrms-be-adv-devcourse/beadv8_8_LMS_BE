package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.app.dto.ChatMessageResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모집용 단체방의 상태·조회 만료·안내 값·초대 결과 기록을 검증한다.
 */
class ChatGroupRoomTest {

    private static final Long RECRUIT_ID = 70L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 단체방은_모집글_호스트_제목_정원을_갖고_사용_중으로_시작한다() {
        ChatRoom room = groupRoom();

        assertThat(room.getRoomType()).isEqualTo(ChatRoomType.GROUP);
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(room.getRecruitId()).isEqualTo(RECRUIT_ID);
        assertThat(room.getOwnerUserId()).isEqualTo(HOST_ID);
        assertThat(room.getTitle()).isEqualTo("주말 합방 모집");
        assertThat(room.getMaxGuestMembers()).isEqualTo(500);
        assertThat(room.getDirectKey()).isNull();
        assertThat(room.isOwnedBy(HOST_ID)).isTrue();
        assertThat(room.isOwnedBy(GUEST_ID)).isFalse();
        assertThat(room.isCurrentGroup()).isTrue();
        assertThat(room.isGroupActive()).isTrue();
        assertThat(room.isViewable(NOW)).isTrue();
        // 1:1 방은 호스트가 없다
        assertThat(directRoom().isOwnedBy(HOST_ID)).isFalse();
    }

    @Test
    void 폐쇄하면_읽기_전용이_되고_전환_시각부터_30일_뒤_조회가_만료된다() {
        ChatRoom room = groupRoom();

        room.closeByHost(NOW);

        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(room.getStatusReason()).isEqualTo(ChatRoomStatusReason.CLOSED_BY_HOST);
        assertThat(room.getReadOnlyAt()).isEqualTo(NOW);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(room.getStatusVersion()).isEqualTo(1);
        assertThat(room.isClosedByHost()).isTrue();
        assertThat(room.isCurrentGroup()).isFalse();
        assertThat(room.isGroupActive()).isFalse();
    }

    @Test
    void 호스트_자격을_잃으면_사유와_함께_읽기_전용이_되고_전환_시각부터_30일_뒤_조회가_만료된다() {
        ChatRoom room = groupRoom();

        room.markHostUnavailable(NOW);

        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(room.getStatusReason()).isEqualTo(ChatRoomStatusReason.HOST_UNAVAILABLE);
        assertThat(room.getReadOnlyAt()).isEqualTo(NOW);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(room.getStatusVersion()).isEqualTo(1);
        assertThat(room.isClosedByHost()).isFalse();
        assertThat(room.isCurrentGroup()).isFalse();
        // 이미 읽기 전용이면 다시 바꾸지 않는다(조회 만료 시각을 늘리지 않는다)
        assertThatThrownBy(() -> room.markHostUnavailable(NOW.plusDays(1))).isInstanceOf(IllegalStateException.class);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(NOW.plusDays(30));
    }

    @Test
    void 조회_만료_시각에_도달한_순간부터_볼_수_없다() {
        ChatRoom room = groupRoom();
        room.closeByHost(NOW);
        var expiresAt = NOW.plusDays(30);

        assertThat(room.isViewable(expiresAt.minusNanos(1000))).isTrue();
        assertThat(room.statusAt(expiresAt.minusNanos(1000))).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(room.isViewable(expiresAt)).isFalse();
        assertThat(room.isHistoryExpired(expiresAt)).isTrue();
        assertThat(room.statusAt(expiresAt)).isEqualTo(ChatRoomStatus.EXPIRED);
        assertThat(room.isViewable(expiresAt.plusDays(1))).isFalse();
    }

    @Test
    void 조회_만료_시각에_도달한_읽기_전용_방만_만료로_바꾸고_상태_버전을_한_번_올린다() {
        ChatRoom room = groupRoom();
        room.closeByHost(NOW);
        var expiresAt = NOW.plusDays(30);

        assertThat(groupRoom().isHistoryExpiryDue(expiresAt)).isFalse();
        assertThat(room.isHistoryExpiryDue(expiresAt.minusNanos(1000))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> room.expireHistory(expiresAt.minusNanos(1000)));
        assertThat(room.isHistoryExpiryDue(expiresAt)).isTrue();

        room.expireHistory(expiresAt);

        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.EXPIRED);
        assertThat(room.getStatusVersion()).isEqualTo(2);
        assertThat(room.getUpdatedAt()).isEqualTo(expiresAt);
        // 사유·전환 시각·만료 시각은 관리자 기록용으로 남긴다
        assertThat(room.getStatusReason()).isEqualTo(ChatRoomStatusReason.CLOSED_BY_HOST);
        assertThat(room.getReadOnlyAt()).isEqualTo(NOW);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(expiresAt);
        // 저장한 만료 상태도 배치 전 조회 때 정한 만료와 같게 보인다
        assertThat(room.isViewable(expiresAt)).isFalse();
        assertThat(room.statusAt(expiresAt)).isEqualTo(ChatRoomStatus.EXPIRED);
        assertThat(room.isCurrentGroup()).isFalse();
        assertThat(room.isGroupActive()).isFalse();
        // 이미 만료된 방은 다시 바꾸지 않는다
        assertThat(room.isHistoryExpiryDue(expiresAt.plusDays(1))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> room.expireHistory(expiresAt.plusDays(1)));
        assertThat(room.getStatusVersion()).isEqualTo(2);
    }

    @Test
    void 읽기_전용_방에는_순번을_발급하지_않고_다시_폐쇄할_수_없다() {
        ChatRoom room = groupRoom();
        room.issueSequence();
        room.closeByHost(NOW);

        assertThatThrownBy(room::issueSequence).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> room.closeByHost(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(room.getLastSequence()).isEqualTo(1);
    }

    @Test
    void 단체방은_종료하지_않는다() {
        assertThatThrownBy(() -> groupRoom().end(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 일대일_방의_조회_가능_여부는_종료로만_정한다() {
        ChatRoom room = directRoom();
        assertThat(room.isViewable(NOW)).isTrue();
        assertThat(room.statusAt(NOW)).isEqualTo(ChatRoomStatus.ACTIVE);

        room.end(NOW);

        assertThat(room.isViewable(NOW)).isFalse();
    }

    @Test
    void 단체방_안내는_본문이_비어_있고_종류별_값_필드_하나만_채운다() {
        ChatRoom room = groupRoom();
        ChatMessage invited = ChatMessage.groupMembersInvited(room, HOST_ID,
                GroupMembersInvitedData.of(HOST_ID, List.of(GUEST_ID, OTHER_INFLUENCER_ID)), NOW);
        ChatMessage kicked = ChatMessage.groupMemberKicked(room, HOST_ID, GroupMemberKickedData.of(HOST_ID, GUEST_ID),
                NOW);
        ChatMessage left = ChatMessage.groupMemberLeft(room, GUEST_ID, GroupMemberLeftData.of(GUEST_ID), NOW);
        ChatMessage closed = ChatMessage.groupRoomClosed(room, HOST_ID, GroupRoomClosedData.of(HOST_ID), NOW);

        JsonNode json = JSON.readTree(JSON.writeValueAsString(ChatMessageResponse.from(invited)));
        assertThat(json.get("messageType").asString()).isEqualTo("SYSTEM");
        assertThat(json.get("content").asString()).isEmpty();
        assertThat(json.get("notificationKind").asString()).isEqualTo("GROUP_MEMBERS_INVITED");
        assertThat(json.get("referenceId").isNull()).isTrue();
        assertThat(JSON.writeValueAsString(json.get("groupMembersInvited")))
                .isEqualTo("{\"v\":1,\"inviterUserId\":1,\"invitedUserIds\":[2,3]}");
        for (String other : List.of("groupMemberKicked", "groupMemberLeft", "groupRoomClosed",
                "appearanceRequestSent", "appearanceApplicationSubmitted")) {
            assertThat(json.get(other).isNull()).as(other).isTrue();
        }
        assertThat(ChatMessageResponse.from(kicked).groupMemberKicked())
                .isEqualTo(new GroupMemberKickedData(1, 1L, 2L));
        assertThat(ChatMessageResponse.from(left).groupMemberLeft()).isEqualTo(new GroupMemberLeftData(1, 2L));
        assertThat(ChatMessageResponse.from(left).senderUserId()).isEqualTo(GUEST_ID);
        assertThat(ChatMessageResponse.from(closed).groupRoomClosed()).isEqualTo(new GroupRoomClosedData(1, 1L));
        // 안내마다 순번을 쓴다
        assertThat(closed.getRoomSequence()).isEqualTo(4);
    }

    @Test
    void 단체방_안내는_일대일_방에_남기지_않는다() {
        assertThatThrownBy(() -> ChatMessage.groupMemberLeft(directRoom(), GUEST_ID, GroupMemberLeftData.of(GUEST_ID),
                NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 초대_결과는_보존_기간이_끝나는_시각부터_새_요청으로_본다() {
        List<ChatGroupInviteOutcome> outcomes = List.of(ChatGroupInviteOutcome.joined(GUEST_ID),
                ChatGroupInviteOutcome.alreadyMember(HOST_ID),
                ChatGroupInviteOutcome.failed(USER_ID, ChatGroupInviteFailure.CHAT_NOT_ALLOWED));
        ChatGroupInviteRequest request = ChatGroupInviteRequest.record(1L, UUID.randomUUID(), HOST_ID, outcomes, NOW);
        Duration retention = Duration.ofHours(24);

        assertThat(request.outcomes()).isEqualTo(outcomes);
        assertThat(request.isRetained(NOW.plusHours(24).minusNanos(1000), retention)).isTrue();
        assertThat(request.isRetained(NOW.plusHours(24), retention)).isFalse();
    }

    private static ChatRoom groupRoom() {
        return ChatRoom.createGroup(RECRUIT_ID, HOST_ID, "주말 합방 모집", 500, NOW);
    }
}
