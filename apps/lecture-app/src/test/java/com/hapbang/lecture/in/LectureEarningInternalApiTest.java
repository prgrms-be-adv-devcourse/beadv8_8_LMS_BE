package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 판매 수익 내부 API(#300)를 내부 API 보안 체인부터 DB까지 관통해 검증한다. 상태 분류는 LectureEarningRepositoryTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureEarningInternalApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String SUMMARY = "/api/v1/internal/lectures/earnings/summary";
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
    private LectureHpTaskRepository lectureHpTaskRepository;

    private final LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);

    @Test
    void 내부_키로_수익_요약을_조회한다() throws Exception {
        save(now);
        confirmed(now);
        confirmed(now);

        mockMvc.perform(internal(get(SUMMARY).param("userId", SELLER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("판매 수익 요약을 조회했습니다."))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(1))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(PRICE_HP * 2))
                .andExpect(jsonPath("$.data.scheduled.count").value(2))
                .andExpect(jsonPath("$.data.onHold.amountHp").value(0))
                .andExpect(jsonPath("$.data.onHold.count").value(0))
                .andExpect(jsonPath("$.data.processing.count").value(0))
                .andExpect(jsonPath("$.data.failed.count").value(0))
                .andExpect(jsonPath("$.data.asOf").value(endsWith("+09:00")));
    }

    @Test
    void 요약_합계는_현재_페이지가_아니라_전체_항목_기준이다() throws Exception {
        save(now);
        save(now);
        save(now);

        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString()).param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(3));
        mockMvc.perform(internal(get(SUMMARY).param("userId", SELLER_ID.toString())))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(3))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(PRICE_HP * 3));
    }

    @Test
    void 수익_목록은_상태로_거르고_페이지를_나눈다() throws Exception {
        LectureOrder scheduled = confirmed(now);
        LectureOrder paidOut = confirmed(now.minusDays(32));
        inTransaction(paidOut, order -> LectureOrderFixture.payOut(order, itemId(order), now));
        save(now);

        // 상태를 주지 않으면 아직 지급하지 않은 수익만 나온다
        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("판매 수익 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[*].status").value(
                        org.hamcrest.Matchers.containsInAnyOrder("CONFIRMATION_PENDING", "SCHEDULED")));
        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString())
                        .param("status", "SCHEDULED").param("status", "COMPLETED").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(2))
                // 지급(now)이 구매 확정(now)과 같은 시각이면 항목 ID 역순이라 나중에 만든 지급 완료 항목이 먼저다
                .andExpect(jsonPath("$.data.content[0].sourceType").value("LECTURE"))
                .andExpect(jsonPath("$.data.content[0].sourceId").value(itemId(paidOut)))
                .andExpect(jsonPath("$.data.content[0].earningType").value("LECTURE_SALE"))
                .andExpect(jsonPath("$.data.content[0].title").value(TITLE))
                .andExpect(jsonPath("$.data.content[0].amountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.content[0].statusUpdatedAt").value(endsWith("+09:00")));
        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString())
                        .param("status", "SCHEDULED").param("status", "COMPLETED").param("size", "1")
                        .param("page", "1")))
                .andExpect(jsonPath("$.data.content[0].sourceId").value(itemId(scheduled)))
                .andExpect(jsonPath("$.data.content[0].status").value("SCHEDULED"));
    }

    @Test
    void 확정할_수_없는_지급_예정_시각은_null이다() throws Exception {
        LectureOrder onHold = save(now);
        inTransaction(onHold, order -> order.fileObjection(itemId(order), BUYER_ID, 500L, "내용이 다릅니다.", now));
        LectureOrder failed = confirmed(now.minusDays(32));
        inTransaction(failed, order -> order.startPayoutItem(itemId(order), now));
        // 지급 작업이 자동 처리를 멈추면 실패다 (D70 (3))
        LectureHpTask deadPayout = LectureHpTask.create(LectureHpTaskPurpose.PAYOUT, "LECTURE-PAYOUT-" + itemId(failed),
                failed.getId(), itemId(failed), BUYER_ID, SELLER_ID, PRICE_HP, now);
        deadPayout.markDead("PAYOUT_ALREADY_SETTLED", now);
        lectureHpTaskRepository.save(deadPayout);
        LectureOrder canceled = save(now);
        inTransaction(canceled, order -> LectureOrderFixture.cancel(order, itemId(order), now));

        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString())
                        .param("status", "ON_HOLD").param("status", "FAILED").param("status", "CANCELED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                // null 필드는 응답에서 빠진다 (공통 Jackson 설정)
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].payoutScheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[2].payoutScheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].statusUpdatedAt").value(endsWith("+09:00")));
    }

    @Test
    void userId가_없거나_상태_값이_잘못되면_400이다() throws Exception {
        mockMvc.perform(internal(get(SUMMARY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
        mockMvc.perform(internal(get(EARNINGS).param("userId", SELLER_ID.toString()).param("status", "PAID")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void 판매_기록이_없는_회원은_0건_0원이다() throws Exception {
        mockMvc.perform(internal(get(SUMMARY).param("userId", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationPending.count").value(0))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(0));
        mockMvc.perform(internal(get(EARNINGS).param("userId", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    void 내부_키가_없거나_틀리면_401이다() throws Exception {
        mockMvc.perform(get(SUMMARY).param("userId", SELLER_ID.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(EARNINGS).param("userId", SELLER_ID.toString())
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 회원_세션만으로는_내부_API를_부를_수_없다() throws Exception {
        // 세션에 저장된 로그인 정보를 읽지 않는다
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(SELLER_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + Role.VERIFIED_INFLUENCER.name())))));
        mockMvc.perform(get(SUMMARY).param("userId", SELLER_ID.toString()).session(session))
                .andExpect(status().isUnauthorized());
        // 인증 정보가 이미 있어도 내부 호출이 아니면 401이다
        mockMvc.perform(get(SUMMARY).param("userId", SELLER_ID.toString())
                        .with(TestLogin.as(SELLER_ID, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_키로는_일반_API를_통과할_수_없다() throws Exception {
        mockMvc.perform(internal(get("/api/v1/lectures/sales")))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    private LectureOrder save(LocalDateTime purchasedAt) {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lectureRepository.save(lecture)),
                purchasedAt));
    }

    /** 구매 시각에 바로 구매 확정한 주문 */
    private LectureOrder confirmed(LocalDateTime purchasedAt) {
        LectureOrder saved = save(purchasedAt);
        inTransaction(saved, order -> order.confirmItem(itemId(order), purchasedAt));
        return saved;
    }

    private void inTransaction(LectureOrder order, Consumer<LectureOrder> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(lectureOrderRepository.findById(order.getId()).orElseThrow()));
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }
}
