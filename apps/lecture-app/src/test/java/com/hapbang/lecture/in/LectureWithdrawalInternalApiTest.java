package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 탈퇴 막힌 이유 내부 API(#625)를 내부 API 보안 체인부터 DB까지 관통해 검증한다.
// 이유 분류는 LectureWithdrawalBlockRepositoryTest, 문장·날짜 규칙은 LectureWithdrawalBlocksTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureWithdrawalInternalApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String BLOCKS = "/api/v1/internal/lectures/users/{userId}/withdrawal-blocks";
    private static final String EARNINGS = "/api/v1/internal/lectures/earnings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);

    @Test
    void 내부_키로_구매자와_판매자의_막힌_이유를_조회한다() throws Exception {
        LectureOrder holding = save(now);
        LectureOrder filed = save(now);
        inTransaction(filed, order -> order.fileObjection(itemId(order), BUYER_ID, 500L, "내용이 다릅니다.", now));
        String payoutDate = itemOf(holding).getPayoutScheduledAt().toLocalDate().toString();

        // 구매자: 보류 중 1건(이의 제기 항목은 이의 제기로만 센다), 이의 제기 1건
        mockMvc.perform(internal(get(BLOCKS, BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("탈퇴 확인을 마쳤습니다."))
                .andExpect(jsonPath("$.data.reasons.length()").value(2))
                .andExpect(jsonPath("$.data.reasons[0].code").value("LECTURE_ORDER_HOLDING"))
                .andExpect(jsonPath("$.data.reasons[0].message")
                        .value("구매한 강의 1건의 이의 제기 기간이 끝나지 않았어요. 기간이 끝난 뒤 탈퇴할 수 있어요."))
                .andExpect(jsonPath("$.data.reasons[0].availableAt").value(payoutDate))
                .andExpect(jsonPath("$.data.reasons[1].code").value("LECTURE_OBJECTION_FILED"))
                // null 필드는 응답에서 빠진다 (공통 Jackson 설정)
                .andExpect(jsonPath("$.data.reasons[1].availableAt").doesNotExist());

        // 판매자: 지급 전 판매 2건(이의 제기로 지급 중단된 건이 있어 날짜를 정할 수 없다), 판매 중 강의 2개
        mockMvc.perform(internal(get(BLOCKS, SELLER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons.length()").value(2))
                .andExpect(jsonPath("$.data.reasons[0].code").value("LECTURE_SALE_PAYOUT_PENDING"))
                .andExpect(jsonPath("$.data.reasons[0].message")
                        .value("아직 지급되지 않은 판매 수익 2건이 있어요. 지급이 끝난 뒤 탈퇴할 수 있어요."))
                .andExpect(jsonPath("$.data.reasons[0].availableAt").doesNotExist())
                .andExpect(jsonPath("$.data.reasons[1].code").value("LECTURE_ON_SALE"))
                .andExpect(jsonPath("$.data.reasons[1].message")
                        .value("판매 중이거나 검수 대기 중인 강의 2개가 있어요. 강의를 삭제한 뒤 탈퇴할 수 있어요."));
    }

    @Test
    void availableAt은_수익_API의_지급_예정_시각과_같은_날짜다() throws Exception {
        LectureOrder sold = save(now);
        deleteLecture(itemOf(sold).getLectureId());

        String earnings = mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode earning = objectMapper.readTree(earnings).at("/data/content/0");
        String payoutDate = OffsetDateTime.parse(earning.get("payoutScheduledAt").asString())
                .atZoneSameInstant(LectureConfig.ZONE).toLocalDate().toString();

        // 강의를 삭제해도 지급은 그대로라 지급 전 판매 건만 남는다 (D5)
        mockMvc.perform(internal(get(BLOCKS, SELLER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons.length()").value(1))
                .andExpect(jsonPath("$.data.reasons[0].code").value("LECTURE_SALE_PAYOUT_PENDING"))
                .andExpect(jsonPath("$.data.reasons[0].availableAt").value(payoutDate));
        mockMvc.perform(internal(get(BLOCKS, BUYER_ID)))
                .andExpect(jsonPath("$.data.reasons[0].code").value("LECTURE_ORDER_HOLDING"))
                .andExpect(jsonPath("$.data.reasons[0].availableAt").value(payoutDate));
    }

    @Test
    void 주문과_강의가_없는_회원은_빈_목록이다() throws Exception {
        // 강의는 복제 회원을 읽지 않는다. 복제 회원이 없는 회원도 같다
        mockMvc.perform(internal(get(BLOCKS, 999L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("탈퇴 확인을 마쳤습니다."))
                .andExpect(jsonPath("$.data.reasons").isArray())
                .andExpect(jsonPath("$.data.reasons.length()").value(0));
    }

    @Test
    void 끝난_주문만_있으면_빈_목록이다() throws Exception {
        LectureOrder canceled = save(now);
        inTransaction(canceled, order -> LectureOrderFixture.cancel(order, itemId(order), now));
        deleteLecture(itemOf(canceled).getLectureId());

        mockMvc.perform(internal(get(BLOCKS, BUYER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons.length()").value(0));
        mockMvc.perform(internal(get(BLOCKS, SELLER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons.length()").value(0));
    }

    @Test
    void 조회해도_주문_항목과_강의가_바뀌지_않는다() throws Exception {
        LectureOrder order = save(now);
        OrderItem before = storedItem(order);
        Lecture lectureBefore = lectureRepository.findById(before.getLectureId()).orElseThrow();

        mockMvc.perform(internal(get(BLOCKS, BUYER_ID))).andExpect(status().isOk());
        mockMvc.perform(internal(get(BLOCKS, SELLER_ID))).andExpect(status().isOk());

        OrderItem after = storedItem(order);
        assertThat(after).usingRecursiveComparison().comparingOnlyFields("status", "objectionStatus",
                "payoutScheduledAt", "confirmedAt", "canceledAt").isEqualTo(before);
        assertThat(lectureRepository.findById(before.getLectureId()).orElseThrow())
                .extracting(Lecture::getStatus, Lecture::getUpdatedAt, Lecture::getVersion)
                .containsExactly(lectureBefore.getStatus(), lectureBefore.getUpdatedAt(), lectureBefore.getVersion());
    }

    @Test
    void 내부_키가_없거나_틀리면_본문_없이_401이다() throws Exception {
        mockMvc.perform(get(BLOCKS, BUYER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(BLOCKS, BUYER_ID)
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void 회원_로그인만으로는_부를_수_없다() throws Exception {
        mockMvc.perform(get(BLOCKS, BUYER_ID).with(GatewayLogin.as(BUYER_ID, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userId가_숫자가_아니면_400_INVALID_REQUEST다() throws Exception {
        mockMvc.perform(internal(get(BLOCKS, "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    /** 판매 중 강의를 하나 등록하고 {@code purchasedAt}에 산 주문 */
    private LectureOrder save(LocalDateTime purchasedAt) {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lectureRepository.save(lecture)),
                purchasedAt));
    }

    private void deleteLecture(Long lectureId) {
        transactionTemplate.executeWithoutResult(status ->
                lectureRepository.findById(lectureId).orElseThrow().delete(SELLER_ID, now));
    }

    private void inTransaction(LectureOrder order, Consumer<LectureOrder> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(lectureOrderRepository.findById(order.getId()).orElseThrow()));
    }

    /** DB에 저장된 주문 항목. 항목은 지연 로딩이라 트랜잭션 안에서 읽는다 */
    private OrderItem storedItem(LectureOrder order) {
        return transactionTemplate.execute(status ->
                itemOf(lectureOrderRepository.findById(order.getId()).orElseThrow()));
    }

    private static OrderItem itemOf(LectureOrder order) {
        return order.getItems().getFirst();
    }

    private static Long itemId(LectureOrder order) {
        return itemOf(order).getId();
    }
}
