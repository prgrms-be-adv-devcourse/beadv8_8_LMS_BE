package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.LectureHpAttemptResult;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskAttempt;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 관리자의 HP 작업 조회·상세·DEAD 재시도 API를 HTTP부터 DB까지 확인한다 (reference.md 8.2.1, D57 (9)).
// 실행·재시도 규칙은 LectureHpTaskExecutorTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureHpTaskAdminApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String BASE = "/api/v1/lectures/admin/hp-tasks";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final LocalDateTime FAILED_AT = CREATED_AT.plusMinutes(3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 관리자_목록은_최신순이고_필터로_거른다() throws Exception {
        save(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", 31L, null);
        LectureHpTask dead = save(LectureHpTaskPurpose.RETURN, "LECTURE-RETURN-32", 32L, null);
        markDead(dead.getId(), "HOLD_ALREADY_CLOSED");
        save(LectureHpTaskPurpose.PAYOUT, "LECTURE-PAYOUT-33", 33L, SELLER_ID);

        mockMvc.perform(get(BASE).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 작업 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].requestId").value("LECTURE-PAYOUT-33"))
                .andExpect(jsonPath("$.data.content[2].requestId").value("LECTURE-CANCEL-31"));

        mockMvc.perform(get(BASE).param("status", "DEAD").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(dead.getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD"))
                .andExpect(jsonPath("$.data.content[0].taskType").value("RELEASE"))
                .andExpect(jsonPath("$.data.content[0].purpose").value("RETURN"))
                .andExpect(jsonPath("$.data.content[0].orderId").value(3))
                .andExpect(jsonPath("$.data.content[0].orderItemId").value(32))
                .andExpect(jsonPath("$.data.content[0].amountHp").value(PRICE_HP))
                .andExpect(jsonPath("$.data.content[0].attemptCount").value(0))
                .andExpect(jsonPath("$.data.content[0].totalAttemptCount").value(0))
                .andExpect(jsonPath("$.data.content[0].manualRetryCount").value(0))
                .andExpect(jsonPath("$.data.content[0].lastErrorCode").value("HOLD_ALREADY_CLOSED"))
                .andExpect(jsonPath("$.data.content[0].lastFailedAt").value("2026-10-29T12:03:00"));

        expectTotal(get(BASE).param("taskType", "PAYOUT"), 1);
        expectTotal(get(BASE).param("orderItemId", String.valueOf(31)), 1);
        expectTotal(get(BASE).param("orderId", "3"), 3);
        expectTotal(get(BASE).param("errorCode", "HOLD_ALREADY_CLOSED"), 1);
        expectTotal(get(BASE).param("referenceType", "LECTURE_ORDER_ITEM"), 3);
        // 강의 작업의 참조 유형은 항상 주문 항목이다
        expectTotal(get(BASE).param("referenceType", "APPEARANCE_REQUEST"), 0);
    }

    @Test
    void 관리자가_아니면_HP_작업을_볼_수_없다() throws Exception {
        mockMvc.perform(get(BASE).with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 상세는_작업과_시도_이력을_준다() throws Exception {
        LectureHpTask task = save(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", 31L, null);
        attempt(task.getId(), 1, LectureHpAttemptResult.UNKNOWN_RESULT, "UNKNOWN_RESULT", "HOLD_STATE_INCONSISTENT");
        attempt(task.getId(), 2, LectureHpAttemptResult.SUCCEEDED, null, null);

        mockMvc.perform(get(BASE + "/{taskId}", task.getId()).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.id").value(task.getId()))
                .andExpect(jsonPath("$.data.task.requestId").value("LECTURE-CANCEL-31"))
                .andExpect(jsonPath("$.data.attempts.length()").value(2))
                .andExpect(jsonPath("$.data.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.data.attempts[0].result").value("UNKNOWN_RESULT"))
                .andExpect(jsonPath("$.data.attempts[0].errorCode").value("UNKNOWN_RESULT"))
                .andExpect(jsonPath("$.data.attempts[0].rawErrorCode").value("HOLD_STATE_INCONSISTENT"))
                .andExpect(jsonPath("$.data.attempts[0].errorDetail").value("진단 정보"))
                .andExpect(jsonPath("$.data.attempts[1].result").value("SUCCEEDED"));
    }

    @Test
    void 없는_작업의_상세는_404_HP_TASK_NOT_FOUND다() throws Exception {
        mockMvc.perform(get(BASE + "/{taskId}", 999_999L).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HP_TASK_NOT_FOUND"));
    }

    @Test
    void DEAD_재시도는_attemptCount만_0으로_하고_204다() throws Exception {
        LectureHpTask task = save(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", 31L, null);
        claimTimes(task.getId(), 3);
        markDead(task.getId(), "UNKNOWN_RESULT");

        mockMvc.perform(post(BASE + "/{taskId}/retry", task.getId())
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());

        LectureHpTask retried = lectureHpTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(retried.getAttemptCount()).isZero();
        assertThat(retried.getTotalAttemptCount()).isEqualTo(3);
        assertThat(retried.getManualRetryCount()).isEqualTo(1);
        assertThat(retried.getLastRetriedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(retried.getLastRetriedAt()).isNotNull();
        assertThat(retried.getLastErrorCode()).isEqualTo("UNKNOWN_RESULT");
    }

    @Test
    void 없는_작업은_404_DEAD가_아니면_409이고_관리자가_아니면_403이다() throws Exception {
        LectureHpTask pending = save(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", 31L, null);

        mockMvc.perform(post(BASE + "/{taskId}/retry", 999_999L).with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HP_TASK_NOT_FOUND"));
        mockMvc.perform(post(BASE + "/{taskId}/retry", pending.getId())
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HP_TASK_NOT_DEAD"));
        markDead(pending.getId(), "UNKNOWN_RESULT");
        mockMvc.perform(post(BASE + "/{taskId}/retry", pending.getId())
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(lectureHpTaskRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(LectureHpTaskStatus.DEAD);
    }

    private void expectTotal(MockHttpServletRequestBuilder request, int total) throws Exception {
        mockMvc.perform(request.with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(total));
    }

    private LectureHpTask save(LectureHpTaskPurpose purpose, String requestId, Long orderItemId, Long receiverId) {
        return lectureHpTaskRepository.save(LectureHpTask.create(purpose, requestId, 3L, orderItemId, BUYER_ID,
                receiverId, PRICE_HP, CREATED_AT));
    }

    private void markDead(Long taskId, String errorCode) {
        transactionTemplate.executeWithoutResult(status ->
                lectureHpTaskRepository.findById(taskId).orElseThrow().markDead(errorCode, FAILED_AT));
    }

    private void claimTimes(Long taskId, int times) {
        LocalDateTime time = CREATED_AT;
        for (int i = 0; i < times; i++) {
            LocalDateTime now = time;
            transactionTemplate.executeWithoutResult(status ->
                    lectureHpTaskRepository.claim(taskId, UUID.randomUUID(), now, now.plusMinutes(5), 10));
            time = time.plusMinutes(6);
        }
    }

    private void attempt(Long taskId, int attemptNo, LectureHpAttemptResult result, String errorCode,
                         String rawErrorCode) {
        LectureHpTaskAttempt attempt = LectureHpTaskAttempt.start(taskId, attemptNo, UUID.randomUUID(), CREATED_AT);
        attempt.finish(result, errorCode, rawErrorCode, errorCode == null ? null : "진단 정보", CREATED_AT);
        lectureHpTaskAttemptRepository.save(attempt);
    }
}
