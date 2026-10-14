package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

/**
 * 강의 주문이 shared BillingPort로 실제 billing 모듈(BillingPortAdapter)을 호출하는지 확인한다.
 * 강의 모듈 테스트는 Fake billing을 쓰므로, 실제 빈 연결과 billing 예외가 강의 트랜잭션을 롤백하는지는 여기서 본다.
 * 잔액이 있는 정상 경로는 billing에 충전 API가 생긴 뒤 추가한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureOrderBillingIntegrationTest {

    private static final Long SELLER_ID = 999_999L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Test
    void 잔액이_없으면_실제_billing이_보류를_거절하고_주문이_남지_않는다() {
        User buyer = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "lecture-order-billing-sub",
                "lecture-order-billing@example.com", "강의구매자");
        Long lectureId = saveOnSaleLecture().getId();
        long ordersBefore = lectureOrderRepository.count();

        // 지갑은 가입 이벤트로 비동기로 만들어진다. 지갑이 생기기 전에는 404 WALLET_NOT_FOUND다
        await().untilAsserted(() -> mockMvc.perform(post("/api/v1/lectures/orders").with(csrf())
                        .with(TestLogin.as(buyer.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + lectureId + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE")));

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
    }

    private Lecture saveOnSaleLecture() {
        LocalDateTime now = LocalDateTime.now();
        Lecture lecture = Lecture.register(SELLER_ID, "방송 장비 세팅 강의", "처음 방송하는 사람을 위한 장비 세팅", 10_000L, null,
                List.of(new LectureContentSpec(101L, "1강 장비 고르기", ContentType.VIDEO)), now);
        lecture.approve(1L, now);
        return lectureRepository.save(lecture);
    }
}
