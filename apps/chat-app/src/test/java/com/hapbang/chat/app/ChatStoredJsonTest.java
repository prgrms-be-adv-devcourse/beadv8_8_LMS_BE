package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatGroupInviteFailure;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;
import com.hapbang.chat.domain.ChatGroupInviteRequest;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.GroupHostUnavailableData;
import com.hapbang.chat.domain.GroupMemberKickedData;
import com.hapbang.chat.domain.GroupMemberLeftData;
import com.hapbang.chat.domain.GroupMembersInvitedData;
import com.hapbang.chat.domain.GroupRoomClosedData;

/**
 * 저장하는 JSON 값: 알림 값을 알림 종류의 record로 읽는지, 읽을 수 없는 값이 이력 조회를 막지 않는지, 쓰는 바이트가 이미 저장된 값과
 * 같은지 검증한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChatStoredJsonTest {

    @Test
    void 저장한_JSON을_종류의_record로_읽는다() {
        AppearanceRequestSentData requestSent = new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00");

        // jsonb는 키 순서를 바꿔 돌려주므로 순서와 관계없이 읽는다
        assertThat(read("""
                {"v":1,"fee":50000,"title":"금요일 밤 합방","scheduledAt":"2026-11-20T21:00:00",
                 "hostNickname":"호스트","acceptDeadline":"2026-11-13T23:59:00"}""", AppearanceRequestSentData.class))
                .isEqualTo(requestSent);
        assertThat(read(ChatStoredJson.writeNotificationData(requestSent), AppearanceRequestSentData.class))
                .isEqualTo(requestSent);
        assertThat(read("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}",
                AppearanceApplicationSubmittedData.class))
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
    }

    @Test
    void 쓰는_JSON은_record_순서의_키와_숫자_타입_그대로다() {
        assertThat(ChatStoredJson.writeNotificationData(new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00"))).isEqualTo("""
                {"v":1,"hostNickname":"호스트","title":"금요일 밤 합방","fee":50000,\
                "scheduledAt":"2026-11-20T21:00:00","acceptDeadline":"2026-11-13T23:59:00"}""");
        assertThat(ChatStoredJson.writeNotificationData(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집")))
                .isEqualTo("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}");
    }

    /** 타입이 다르거나 빠진 값을 v1 값처럼 바꿔 읽지 않는다. 프론트가 대체 표시를 쓸 수 있도록 null로 둔다 */
    @ParameterizedTest
    @ValueSource(strings = {
            // 버전이 소수: 정수로 잘라 읽지 않는다
            "{\"v\":1.9,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 금액이 문자열: 숫자로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":\"50000\",\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 닉네임이 숫자: 문자열로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":123,\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 일시가 숫자·불리언: 문자열로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":1.5,\"acceptDeadline\":true}",
            // 기본 타입(v)이 없음: 0으로 채워 읽지 않는다
            "{\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 기본 타입(fee)이 null
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":null,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 필요한 키(title)가 없음
            "{\"v\":1,\"hostNickname\":\"호스트\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 필요한 키(hostNickname)가 null
            "{\"v\":1,\"hostNickname\":null,\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}"
    })
    void 타입이_다르거나_빠진_값은_바꿔_읽지_않고_null이다(String json, CapturedOutput output) {
        assertThat(read(json, AppearanceRequestSentData.class)).isNull();
        assertThat(output).contains("알림 값을 읽을 수 없어 비워 둠 type=AppearanceRequestSentData");
    }

    @Test
    void 지원_알림도_타입이_다르면_null이다() {
        assertThat(read("{\"v\":1,\"guestNickname\":false,\"recruitTitle\":\"모집\"}",
                AppearanceApplicationSubmittedData.class)).isNull();
        assertThat(read("{\"v\":\"1\",\"guestNickname\":\"게스트\",\"recruitTitle\":\"모집\"}",
                AppearanceApplicationSubmittedData.class)).isNull();
    }

    @Test
    void 같은_버전에서_더한_키는_무시하고_읽는다() {
        assertThat(read("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"모집\",\"added\":true}",
                AppearanceApplicationSubmittedData.class))
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "모집"));
    }

    @Test
    void 값이_없는_일반_메시지는_null이다() {
        assertThat(ChatStoredJson.readAppearanceRequestSent(ChatMessage.text(directRoom(), HOST_ID, "본문", null, NOW)))
                .isNull();
    }

    @Test
    void 읽을_수_없는_값은_예외_없이_null이다() {
        assertThat(read("{not-json", AppearanceRequestSentData.class)).isNull();
        assertThat(read("[1,2]", AppearanceRequestSentData.class)).isNull();
        assertThat(read("{\"fee\":\"많이\"}", AppearanceRequestSentData.class)).isNull();
    }

    /** 도메인 리팩터링 전 코드가 쓴 값과 바이트까지 같게 쓰고, 그 값을 그대로 읽는다(이미 DB와 STOMP·이력 응답에 나간 값이다) */
    @Test
    void 이미_저장된_알림_값과_같은_바이트로_쓰고_그대로_읽는다() {
        AppearanceRequestSentData requestSent = new AppearanceRequestSentData(1, "호스트", "금요일 밤 \"합방\"", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:30");
        String requestSentJson = """
                {"v":1,"hostNickname":"호스트","title":"금요일 밤 \\"합방\\"","fee":50000,\
                "scheduledAt":"2026-11-20T21:00:00","acceptDeadline":"2026-11-13T23:59:30"}""";
        assertThat(ChatStoredJson.writeNotificationData(requestSent)).isEqualTo(requestSentJson);
        assertThat(read(requestSentJson, AppearanceRequestSentData.class)).isEqualTo(requestSent);

        AppearanceApplicationSubmittedData submitted = new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집");
        String submittedJson = "{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}";
        assertThat(ChatStoredJson.writeNotificationData(submitted)).isEqualTo(submittedJson);
        assertThat(read(submittedJson, AppearanceApplicationSubmittedData.class)).isEqualTo(submitted);

        ChatRoom group = ChatRoom.createGroup(10L, HOST_ID, "단체방", 10, NOW);
        assertGroupNotice(GroupMembersInvitedData.of(1L, List.of(2L, 3L)),
                "{\"v\":1,\"inviterUserId\":1,\"invitedUserIds\":[2,3]}",
                json -> ChatMessage.groupMembersInvited(group, HOST_ID, json, NOW), ChatStoredJson::readGroupMembersInvited);
        assertGroupNotice(GroupMemberKickedData.of(1L, 2L), "{\"v\":1,\"kickedByUserId\":1,\"kickedUserId\":2}",
                json -> ChatMessage.groupMemberKicked(group, HOST_ID, json, NOW), ChatStoredJson::readGroupMemberKicked);
        assertGroupNotice(GroupMemberLeftData.of(2L), "{\"v\":1,\"leftUserId\":2}",
                json -> ChatMessage.groupMemberLeft(group, GUEST_ID, json, NOW), ChatStoredJson::readGroupMemberLeft);
        assertGroupNotice(GroupRoomClosedData.of(1L), "{\"v\":1,\"closedByUserId\":1}",
                json -> ChatMessage.groupRoomClosed(group, HOST_ID, json, NOW), ChatStoredJson::readGroupRoomClosed);
        assertGroupNotice(GroupHostUnavailableData.of(1L), "{\"v\":1,\"hostUserId\":1}",
                json -> ChatMessage.groupHostUnavailable(group, HOST_ID, json, NOW),
                ChatStoredJson::readGroupHostUnavailable);
    }

    @Test
    void 이미_저장된_초대_결과와_같은_바이트로_쓰고_그대로_읽는다() {
        List<ChatGroupInviteOutcome> outcomes = List.of(ChatGroupInviteOutcome.joined(2L),
                ChatGroupInviteOutcome.alreadyMember(3L), ChatGroupInviteOutcome.failed(4L, ChatGroupInviteFailure.ROOM_FULL));
        String stored = """
                [{"userId":2,"result":"JOINED","reason":null},{"userId":3,"result":"ALREADY_MEMBER","reason":null},\
                {"userId":4,"result":"FAILED","reason":"ROOM_FULL"}]""";

        assertThat(ChatStoredJson.writeInviteOutcomes(outcomes)).isEqualTo(stored);
        assertThat(ChatStoredJson.readInviteOutcomes(ChatGroupInviteRequest.record(10L, UUID.randomUUID(), HOST_ID, stored,
                NOW))).isEqualTo(outcomes);
    }

    @Test
    void 다른_종류의_메시지에서는_그_종류의_값을_읽지_않는다() {
        ChatMessage requestSent = ChatMessage.appearanceRequestSent(directRoom(), HOST_ID, 10L,
                "{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"모집\"}", NOW);

        assertThat(ChatStoredJson.readAppearanceApplicationSubmitted(requestSent)).isNull();
        assertThat(ChatStoredJson.readAppearanceRequestSent(ChatMessage.text(directRoom(), HOST_ID, "안녕하세요", null, NOW)))
                .isNull();
    }

    private static <T> void assertGroupNotice(T data, String stored,
            Function<String, ChatMessage> notice, Function<ChatMessage, T> reader) {
        assertThat(ChatStoredJson.writeNotificationData(data)).isEqualTo(stored);
        assertThat(reader.apply(notice.apply(stored))).isEqualTo(data);
    }

    /** 출연 알림 메시지에 담아 그 종류로 읽는다 */
    @SuppressWarnings("unchecked")
    private static <T> T read(String json, Class<T> type) {
        if (type == AppearanceRequestSentData.class) {
            return (T) ChatStoredJson.readAppearanceRequestSent(
                    ChatMessage.appearanceRequestSent(directRoom(), HOST_ID, 10L, json, NOW));
        }
        return (T) ChatStoredJson.readAppearanceApplicationSubmitted(
                ChatMessage.appearanceApplicationSubmitted(directRoom(), GUEST_ID, 20L, 7L, json, NOW));
    }
}
