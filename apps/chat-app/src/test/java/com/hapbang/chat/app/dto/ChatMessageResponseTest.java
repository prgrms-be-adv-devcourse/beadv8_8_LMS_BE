package com.hapbang.chat.app.dto;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 응답(REST 이력·STOMP 프레임 공용)이 알림 값을 알림 종류별 필드의 객체로 내보내고, 다른 종류의 필드는 null로 두는지 검증한다.
 */
class ChatMessageResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 출연_요청_알림은_appearanceRequestSent를_객체로_내보내고_본문은_비어_있다() {
        AppearanceRequestDto request = new AppearanceRequestDto(10L, HOST_ID, GUEST_ID, "금요일 밤 합방", 50_000L,
                LocalDateTime.of(2026, 11, 20, 21, 0), LocalDateTime.of(2026, 11, 13, 23, 59, 30, 123_000_000));
        ChatMessage message = ChatMessage.appearanceRequestSent(directRoom(), HOST_ID, 10L,
                AppearanceRequestSentData.of("호스트", request), NOW);

        ChatMessageResponse response = ChatMessageResponse.of(message, GUEST_ID);
        JsonNode json = JSON.readTree(JSON.writeValueAsString(response));

        assertThat(json.get("content").asString()).isEmpty();
        assertThat(json.get("notificationKind").asString()).isEqualTo("APPEARANCE_REQUEST_SENT");
        assertThat(json.has("appearanceApplicationSubmitted")).isTrue();
        assertThat(json.get("appearanceApplicationSubmitted").isNull()).isTrue();
        JsonNode data = json.get("appearanceRequestSent");
        assertThat(data.isObject()).isTrue();
        assertThat(data.size()).isEqualTo(6);
        assertThat(data.get("v").isNumber()).isTrue();
        assertThat(data.get("v").asInt()).isEqualTo(1);
        assertThat(data.get("hostNickname").asString()).isEqualTo("호스트");
        assertThat(data.get("title").asString()).isEqualTo("금요일 밤 합방");
        assertThat(data.get("fee").isNumber()).isTrue();
        assertThat(data.get("fee").asLong()).isEqualTo(50_000L);
        // KST 일시를 오프셋 없이 초 단위까지 적는다 (초 아래는 버린다)
        assertThat(data.get("scheduledAt").asString()).isEqualTo("2026-11-20T21:00:00");
        assertThat(data.get("acceptDeadline").asString()).isEqualTo("2026-11-13T23:59:30");
    }

    @Test
    void 알림_값_JSON은_키_이름과_순서와_타입이_계약과_글자_그대로_같다() {
        AppearanceRequestDto request = new AppearanceRequestDto(10L, HOST_ID, GUEST_ID, "금요일 밤 합방", 50_000L,
                LocalDateTime.of(2026, 11, 20, 21, 0), LocalDateTime.of(2026, 11, 13, 23, 59));
        AppearanceApplicationDto application = new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID,
                GUEST_ID, NOW);

        ChatMessageResponse requestSent = ChatMessageResponse.from(ChatMessage.appearanceRequestSent(directRoom(),
                HOST_ID, 10L, AppearanceRequestSentData.of("호스트", request), NOW));
        ChatMessageResponse applicationSubmitted = ChatMessageResponse.from(ChatMessage.appearanceApplicationSubmitted(
                directRoom(), GUEST_ID, 20L, 7L, AppearanceApplicationSubmittedData.of("게스트", application), NOW));

        assertThat(JSON.writeValueAsString(requestSent.appearanceRequestSent())).isEqualTo("""
                {"v":1,"hostNickname":"호스트","title":"금요일 밤 합방","fee":50000,\
                "scheduledAt":"2026-11-20T21:00:00","acceptDeadline":"2026-11-13T23:59:00"}""");
        assertThat(JSON.writeValueAsString(applicationSubmitted.appearanceApplicationSubmitted()))
                .isEqualTo("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}");
    }

    @Test
    void 일반_메시지의_알림_값_필드는_모두_null이고_본문을_그대로_내보낸다() {
        ChatMessage message = ChatMessage.text(directRoom(), HOST_ID, "안녕하세요", "client-1", NOW);

        JsonNode json = JSON.readTree(JSON.writeValueAsString(ChatMessageResponse.of(message, HOST_ID)));

        for (String field : new String[] {"appearanceRequestSent", "appearanceApplicationSubmitted"}) {
            assertThat(json.has(field)).isTrue();
            assertThat(json.get(field).isNull()).isTrue();
        }
        assertThat(json.get("content").asString()).isEqualTo("안녕하세요");
    }

    @Test
    void 전송_ID를_붙여도_알림_값은_그대로다() {
        AppearanceApplicationDto application = new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID,
                GUEST_ID, NOW);
        ChatMessage message = ChatMessage.appearanceApplicationSubmitted(directRoom(), GUEST_ID, 20L, 7L,
                AppearanceApplicationSubmittedData.of("게스트", application), NOW);

        ChatMessageResponse response = ChatMessageResponse.from(message).withClientMessageId("client-1");

        assertThat(response.appearanceApplicationSubmitted())
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
        assertThat(response.appearanceRequestSent()).isNull();
    }
}
