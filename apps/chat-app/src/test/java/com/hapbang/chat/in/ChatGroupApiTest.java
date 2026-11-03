package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.chat.app.ChatGroupTestConfig;
import com.hapbang.chat.app.ChatRecruitSourceException.Reason;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.InMemoryChatRecruitSource;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.auth.GatewayLogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 모집용 단체방 REST API의 경로·상태 코드·RsData/ProblemDetail 형식을 실제 보안 필터(Gateway 사용자 헤더)와 함께 검증한다.
 * 업무 규칙은 {@code ChatGroupRoomFacadeTest}가 본다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(ChatGroupTestConfig.class)
class ChatGroupApiTest {

    private static final Long RECRUIT_ID = 70L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private InMemoryChatRecruitSource recruits;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        recruits.reset();
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "주말 합방 모집", "RECRUITING");
        recruits.addApplicants(RECRUIT_ID, GUEST_ID, OTHER_INFLUENCER_ID);
    }

    @Test
    void 단체방을_만들면_201_다시_요청하면_같은_방으로_200이고_단체방_정보를_싣는다() throws Exception {
        long roomId = createGroup();

        mockMvc.perform(post("/api/v1/chats/groups").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recruitId\":70}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatRoomId").value(roomId));
        mockMvc.perform(get("/api/v1/chats/" + roomId).with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomType").value("GROUP"))
                .andExpect(jsonPath("$.data.members.length()").value(0))
                .andExpect(jsonPath("$.data.group.recruitId").value(70))
                .andExpect(jsonPath("$.data.group.hostUserId").value(HOST_ID))
                .andExpect(jsonPath("$.data.group.title").value("주말 합방 모집"))
                .andExpect(jsonPath("$.data.group.maxGuestMembers").value(500))
                .andExpect(jsonPath("$.data.group.myParticipationVersion").value(1));
        mockMvc.perform(get("/api/v1/chats/groups").param("recruitId", "70")
                        .with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentRoom.chatRoomId").value(roomId))
                .andExpect(jsonPath("$.data.previousRooms.length()").value(0));
    }

    @Test
    void 출연_앱이_응답하지_않으면_503_ProblemDetail이다() throws Exception {
        recruits.failWith(Reason.UNAVAILABLE);

        mockMvc.perform(post("/api/v1/chats/groups").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recruitId\":70}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPEARANCE_UNAVAILABLE"));
    }

    @Test
    void 초대는_회원별_결과로_200이고_요청_ID_누락과_한_요청_상한_초과는_400이다() throws Exception {
        long roomId = createGroup();

        mockMvc.perform(post("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(UUID.randomUUID(), List.of(GUEST_ID, 99L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].userId").value(GUEST_ID))
                .andExpect(jsonPath("$.data.results[0].result").value("JOINED"))
                .andExpect(jsonPath("$.data.results[1].result").value("FAILED"))
                .andExpect(jsonPath("$.data.results[1].reason").value("NOT_APPLICANT"));
        mockMvc.perform(post("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        List<Long> tooMany = LongStream.rangeClosed(100, 150).boxed().toList();
        mockMvc.perform(post("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody(UUID.randomUUID(), tooMany)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 자기_나가기_경로와_강퇴_경로를_구분한다() throws Exception {
        long roomId = createGroup();
        mockMvc.perform(post("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody(UUID.randomUUID(), List.of(GUEST_ID))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/chats/" + roomId + "/members/me")
                        .with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_CANNOT_LEAVE"));
        mockMvc.perform(get("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(GUEST_ID, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.length()").value(2))
                .andExpect(jsonPath("$.data.members[1].userId").value(GUEST_ID))
                .andExpect(jsonPath("$.data.members[1].participationVersion").value(1));
        mockMvc.perform(delete("/api/v1/chats/" + roomId + "/members/" + GUEST_ID)
                        .with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/chats/" + roomId + "/members/" + GUEST_ID)
                        .param("participationVersion", "1")
                        .with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/chats/" + roomId + "/members").with(GatewayLogin.as(GUEST_ID, Role.INFLUENCER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_CHAT_ROOM_MEMBER"));
    }

    @Test
    void 폐쇄하면_읽기_전용과_조회_만료_시각을_돌려준다() throws Exception {
        long roomId = createGroup();

        mockMvc.perform(post("/api/v1/chats/" + roomId + "/close").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READ_ONLY"))
                .andExpect(jsonPath("$.data.statusReason").value("CLOSED_BY_HOST"))
                .andExpect(jsonPath("$.data.readOnlyAt").isNotEmpty())
                .andExpect(jsonPath("$.data.historyExpiresAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/chats/" + roomId + "/invite-candidates")
                        .with(GatewayLogin.as(HOST_ID, Role.INFLUENCER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_ROOM_NOT_ACTIVE"));
    }

    @Test
    void 일반_유저는_단체방을_만들_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/chats/groups").with(GatewayLogin.as(USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recruitId\":70}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Swagger_명세에_단체방_안내_값이_종류별_필드로_나온다() throws Exception {
        mockMvc.perform(get("/api/v1/chats/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.groupMembersInvited").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.groupMemberKicked").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.groupMemberLeft").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.groupRoomClosed").exists())
                .andExpect(jsonPath("$.components.schemas.GroupMembersInvitedData.properties.invitedUserIds").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chats/groups'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chats/{chatRoomId}/members/{targetUserId}'].delete").exists());
    }

    private long createGroup() throws Exception {
        String body = mockMvc.perform(post("/api/v1/chats/groups").with(GatewayLogin.as(HOST_ID, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recruitId\":70}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("chatRoomId").asLong();
    }

    private static String inviteBody(UUID requestId, List<Long> userIds) {
        return JSON.writeValueAsString(Map.of("requestId", requestId.toString(), "userIds", userIds));
    }
}
