package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;

/**
 * N9 채팅 단체방 3종의 알림 초안과 문구(#921, NT-GEN-24). 받는 사람(ND-011, NT-GEN-08)·원본 대상 ID(ND-130)·이동 대상(ND-131)·
 * 발생 시각·문구(ND-132, 알림 종류 0.12)·필수 값 검증(ND-129).
 */
class GroupRoomAlertsTest {

    private static final long ROOM = 31L;
    private static final long RECRUIT = 41L;
    private static final long HOST = 7L;
    private static final String TITLE = "봄맞이 합방";
    private static final Instant AT = Instant.parse("2026-11-12T01:00:00Z");

    @Test
    void 초대는_이번_초대로_참여한_회원마다_같은_사건_ID로_만든다() {
        List<NotificationDraft> drafts = GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(ROOM, 51L, RECRUIT, TITLE, HOST, List.of(101L, 102L), AT));

        assertThat(drafts).extracting(NotificationDraft::receiverUserId).containsExactly(101L, 102L);
        // 받는 사람이 여럿이어도 원본 대상 ID는 사건 하나다. 유일 키에 받는 사람이 있어 각 1건이 된다(ND-019·ND-130)
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("GROUP_MEMBERS_INVITED");
            assertThat(draft.sourceEventType()).isEqualTo("GroupMembersInvitedEvent");
            assertThat(draft.sourceTargetId()).isEqualTo("31:51");
            assertThat(draft.targetType()).isEqualTo("CHAT_ROOM");
            assertThat(draft.targetId()).isEqualTo("31");
            assertThat(draft.occurredAt()).isEqualTo(AT);
            assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "title");
            assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
            assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
            assertThat(render(draft)).isEqualTo("'봄맞이 합방' 단체방에 초대되었습니다.");
        });
    }

    @Test
    void 강퇴는_강퇴된_회원에게_참여_세대를_넣은_사건_ID로_만들고_이동_대상은_없다() {
        NotificationDraft draft = GroupRoomAlerts.kicked(
                new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, 101L, 2, AT));

        assertThat(draft.receiverUserId()).isEqualTo(101L);
        assertThat(draft.kind()).isEqualTo("GROUP_MEMBER_KICKED");
        assertThat(draft.sourceEventType()).isEqualTo("GroupMemberKickedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("31:101:2");
        assertThat(draft.targetType()).isNull();
        assertThat(draft.targetId()).isNull();
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "title");
        assertThat(render(draft)).isEqualTo("'봄맞이 합방' 단체방에서 내보내졌습니다.");
    }

    @Test
    void 재초대_뒤_다시_강퇴되면_사건_ID가_다르다() {
        NotificationDraft first = GroupRoomAlerts.kicked(
                new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, 101L, 1, AT));
        NotificationDraft second = GroupRoomAlerts.kicked(
                new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, 101L, 2, AT.plusSeconds(60)));

        assertThat(first.sourceTargetId()).isNotEqualTo(second.sourceTargetId());
    }

    @Test
    void 폐쇄는_폐쇄한_호스트를_뺀_참여자마다_방_ID로_만든다() {
        List<NotificationDraft> drafts = GroupRoomAlerts.closed(
                new GroupRoomClosedEvent(ROOM, RECRUIT, TITLE, HOST, List.of(HOST, 101L, 102L), AT));

        assertThat(drafts).extracting(NotificationDraft::receiverUserId).containsExactly(101L, 102L);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("GROUP_ROOM_CLOSED");
            assertThat(draft.sourceEventType()).isEqualTo("GroupRoomClosedEvent");
            assertThat(draft.sourceTargetId()).isEqualTo("31");
            assertThat(draft.targetType()).isEqualTo("CHAT_ROOM");
            assertThat(draft.targetId()).isEqualTo("31");
            assertThat(draft.occurredAt()).isEqualTo(AT);
            assertThat(render(draft)).isEqualTo("'봄맞이 합방' 단체방이 닫혔습니다. 새 메시지는 보낼 수 없습니다.");
        });
    }

    @Test
    void 폐쇄_때_호스트만_남았으면_알림이_없다() {
        assertThat(GroupRoomAlerts.closed(new GroupRoomClosedEvent(ROOM, RECRUIT, TITLE, HOST, List.of(HOST), AT)))
                .isEmpty();
    }

    @Test
    void 필수_값이_빈_초대는_거절한다() {
        List<Long> one = List.of(101L);
        assertRejected(() -> GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(null, 51L, RECRUIT, TITLE, HOST, one, AT)));
        assertRejected(() -> GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(ROOM, null, RECRUIT, TITLE, HOST, one, AT)));
        assertRejected(() -> GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(ROOM, 51L, RECRUIT, null, HOST, one, AT)));
        assertRejected(() -> GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(ROOM, 51L, RECRUIT, TITLE, HOST, one, null)));
        // 계약상 참여한 회원이 없으면 발행하지 않는다. 빈 목록은 잘못된 이벤트다
        assertRejected(() -> GroupRoomAlerts.invited(
                new GroupMembersInvitedEvent(ROOM, 51L, RECRUIT, TITLE, HOST, List.of(), AT)));
        // 목록이 없거나 null 원소가 있는 본문은 레코드 생성자(List.copyOf)가 역직렬화 단계에서 막아 DLT로 간다(통합 시험에서 본다)
    }

    @Test
    void 필수_값이_빈_강퇴는_거절한다() {
        assertRejected(() -> GroupRoomAlerts.kicked(new GroupMemberKickedEvent(null, RECRUIT, TITLE, HOST, 101L, 1, AT)));
        assertRejected(() -> GroupRoomAlerts.kicked(new GroupMemberKickedEvent(ROOM, RECRUIT, null, HOST, 101L, 1, AT)));
        assertRejected(() -> GroupRoomAlerts.kicked(new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, null, 1, AT)));
        assertRejected(() -> GroupRoomAlerts.kicked(new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, 101L, 1, null)));
        // 참여 세대는 1부터다. 0은 값이 빠진 본문(int 기본값)이다
        assertRejected(() -> GroupRoomAlerts.kicked(new GroupMemberKickedEvent(ROOM, RECRUIT, TITLE, HOST, 101L, 0, AT)));
    }

    @Test
    void 필수_값이_빈_폐쇄는_거절한다() {
        List<Long> members = List.of(HOST, 101L);
        assertRejected(() -> GroupRoomAlerts.closed(new GroupRoomClosedEvent(null, RECRUIT, TITLE, HOST, members, AT)));
        assertRejected(() -> GroupRoomAlerts.closed(new GroupRoomClosedEvent(ROOM, RECRUIT, null, HOST, members, AT)));
        // 호스트를 알아야 받는 사람에서 뺄 수 있다
        assertRejected(() -> GroupRoomAlerts.closed(new GroupRoomClosedEvent(ROOM, RECRUIT, TITLE, null, members, AT)));
        assertRejected(() -> GroupRoomAlerts.closed(new GroupRoomClosedEvent(ROOM, RECRUIT, TITLE, HOST, members, null)));
        // 계약상 호스트가 늘 들어 있다. 빈 목록은 잘못된 이벤트다
        assertRejected(() -> GroupRoomAlerts.closed(new GroupRoomClosedEvent(ROOM, RECRUIT, TITLE, HOST, List.of(), AT)));
    }

    private static void assertRejected(Runnable convert) {
        assertThatThrownBy(convert::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static String render(NotificationDraft draft) {
        return GroupRoomAlertTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}
