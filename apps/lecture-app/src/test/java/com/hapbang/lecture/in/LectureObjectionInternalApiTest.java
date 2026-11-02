package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureObjectionCommandRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 이의 제기 접수 내부 API(#278)를 내부 API 보안 체인부터 명령 이력까지 관통해 검증한다. 항목 규칙은 LectureObjectionFacadeTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureObjectionInternalApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String FILE = "/api/v1/internal/lectures/objections/file";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureObjectionCommandRepository lectureObjectionCommandRepository;

    @Test
    void 내부_키로_접수하면_항목이_접수되고_같은_요청을_다시_보내도_같은_결과다() throws Exception {
        Long itemId = itemId(save(LocalDateTime.now(LectureConfig.ZONE)));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(internal(post(FILE)).content(body(12L, itemId, BUYER_ID, "내용이 다릅니다.")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusCode").value(200))
                    .andExpect(jsonPath("$.message").value("이의 제기를 접수했습니다."))
                    .andExpect(jsonPath("$.data.commandId").value("FILE:12"))
                    .andExpect(jsonPath("$.data.inquiryId").value(12))
                    .andExpect(jsonPath("$.data.orderItemId").value(itemId));
        }

        assertThat(lectureOrderRepository.findItemOfBuyer(itemId, BUYER_ID)).hasValueSatisfying(item -> {
            assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
            assertThat(item.getObjectionInquiryId()).isEqualTo(12L);
        });
        assertThat(lectureObjectionCommandRepository.findAll()).hasSize(1);
    }

    @Test
    void 거절은_사유_코드로_응답하고_다시_보내도_같다() throws Exception {
        Long itemId = itemId(save(LocalDateTime.now(LectureConfig.ZONE).minusDays(31)));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(internal(post(FILE)).content(body(12L, itemId, BUYER_ID, "내용이 다릅니다.")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OBJECTION_PERIOD_EXPIRED"));
        }
        mockMvc.perform(internal(post(FILE)).content(body(13L, itemId, 999L, "내용이 다릅니다.")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBJECTION_NOT_BUYER"));
    }

    @Test
    void 같은_명령_ID로_다른_내용을_보내면_409_OBJECTION_COMMAND_CONFLICT다() throws Exception {
        Long itemId = itemId(save(LocalDateTime.now(LectureConfig.ZONE)));
        mockMvc.perform(internal(post(FILE)).content(body(12L, itemId, BUYER_ID, "내용이 다릅니다.")))
                .andExpect(status().isOk());

        mockMvc.perform(internal(post(FILE)).content(body(12L, itemId, BUYER_ID, "다른 사유")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBJECTION_COMMAND_CONFLICT"));
    }

    @Test
    void 빠진_값이나_빈_사유는_400이다() throws Exception {
        mockMvc.perform(internal(post(FILE)).content("""
                        {"commandId":"FILE:12","inquiryId":12,"buyerUserId":2,"reason":"사유"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(post(FILE)).content(body(12L, 1L, BUYER_ID, " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 내부_키가_없거나_회원_로그인이면_401이다() throws Exception {
        mockMvc.perform(post(FILE).contentType(MediaType.APPLICATION_JSON).content(body(12L, 1L, BUYER_ID, "사유")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(FILE).contentType(MediaType.APPLICATION_JSON).content(body(12L, 1L, BUYER_ID, "사유"))
                        .with(GatewayLogin.as(BUYER_ID, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
        assertThat(lectureObjectionCommandRepository.findAll()).isEmpty();
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String body(Long inquiryId, Long orderItemId, Long buyerUserId, String reason) {
        return """
                {"commandId":"FILE:%d","inquiryId":%d,"orderItemId":%d,"buyerUserId":%d,"reason":"%s"}"""
                .formatted(inquiryId, inquiryId, orderItemId, buyerUserId, reason);
    }

    private LectureOrder save(LocalDateTime purchasedAt) {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lectureRepository.save(lecture)),
                purchasedAt));
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }
}
