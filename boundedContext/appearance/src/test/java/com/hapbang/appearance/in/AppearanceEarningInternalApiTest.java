package com.hapbang.appearance.in;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.app.AppearanceFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

/**
 * 출연 수익 내부 API (#301)를 내부 API 보안 체인(global)부터 DB까지 관통해 확인한다. 상태 분류는 {@code AppearanceEarningQueryTest}가 맡는다.
 * <ul>
 *     <li>응답 모양은 강의 내부 API와 같다 (docs/api/internal-earnings.md): {@code RsData}, 페이지 {@code {content, page}},
 *     {@code sourceType} 문자열, null 필드 생략. 다른 점: 시각은 offset 없는 KST, 요약에 {@code asOf} 없음.</li>
 *     <li>흐름은 요청 전에 Facade로 만든다 (MockMvc 요청이 끝나면 테스트의 로그인 정보를 지운다). 로그인 정보는 흐름을 만들 때만 두고,
 *     요청은 내부 API 키만으로 보낸다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
class AppearanceEarningInternalApiTest {

    private static final String SUMMARY = "/api/v1/internal/appearances/earnings/summary";
    private static final String EARNINGS = "/api/v1/internal/appearances/earnings";
    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    /** offset 없는 ISO 날짜·시각 (예: 2026-10-20T10:00:00.123456) */
    private static final String LOCAL_DATE_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    /** 확인 대기 20,000 1건 + 처리 중(확인했지만 Billing 결과 모름) 22,000 1건. 처리 중 출연 ID */
    private Long pendingAndProcessing() {
        return asInfluencer(() -> {
            establish(20_000);
            Long processing = establish(22_000);
            clock.advanceTo(appearanceRepository.findById(processing).orElseThrow().getScheduledAt().plusHours(1));
            BillingStubs.unavailable(billingClient);
            appearanceFacade.confirm(HOST, processing);
            BillingStubs.available(billingClient);
            return processing;
        });
    }

    private Long establish(long fee) {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now(), fee));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 흐름을 만드는 동안만 인플루언서로 로그인해 둔다 */
    private static <T> T asInfluencer(Supplier<T> flow) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "influencer", null, List.of(new SimpleGrantedAuthority("ROLE_INFLUENCER"))));
        try {
            return flow.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("요약: 내부 키로 상태별 {amountHp, count}를 RsData로 준다. 지급 예정은 0, asOf는 없다")
    void summary() throws Exception {
        pendingAndProcessing();

        mockMvc.perform(internal(get(SUMMARY).param("userId", GUEST.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("출연 수익 요약을 조회했습니다."))
                .andExpect(jsonPath("$.data.confirmationPending.amountHp").value(20_000))
                .andExpect(jsonPath("$.data.confirmationPending.count").value(1))
                .andExpect(jsonPath("$.data.scheduled.amountHp").value(0))
                .andExpect(jsonPath("$.data.scheduled.count").value(0))
                .andExpect(jsonPath("$.data.onHold.count").value(0))
                .andExpect(jsonPath("$.data.processing.amountHp").value(22_000))
                .andExpect(jsonPath("$.data.processing.count").value(1))
                .andExpect(jsonPath("$.data.failed.count").value(0))
                .andExpect(jsonPath("$.data.asOf").doesNotExist());
        // 종류 필터: 위약금만 고르면 0
        mockMvc.perform(internal(get(SUMMARY).param("userId", GUEST.toString())
                        .param("earningType", "APPEARANCE_PENALTY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationPending.count").value(0))
                .andExpect(jsonPath("$.data.processing.count").value(0));
    }

    @Test
    @DisplayName("목록: 강의와 같은 필드·페이지 모양. sourceType APPEARANCE, 시각은 offset 없는 KST, 지급 예정 시각(null)은 응답에서 빠진다")
    void earnings() throws Exception {
        Long processing = pendingAndProcessing();

        mockMvc.perform(internal(get(EARNINGS).param("userId", GUEST.toString())
                        .param("status", "PROCESSING").param("status", "COMPLETED").param("earningType", "APPEARANCE_FEE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출연 수익 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].sourceType").value("APPEARANCE"))
                .andExpect(jsonPath("$.data.content[0].sourceId").value(processing))
                .andExpect(jsonPath("$.data.content[0].earningType").value("APPEARANCE_FEE"))
                .andExpect(jsonPath("$.data.content[0].title").value(
                        AppearanceRequestFixture.content(clock.now()).title()))
                .andExpect(jsonPath("$.data.content[0].amountHp").value(22_000))
                .andExpect(jsonPath("$.data.content[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.content[0].payoutScheduledAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].statusUpdatedAt").value(matchesPattern(LOCAL_DATE_TIME)));
    }

    @Test
    @DisplayName("상태를 주지 않으면 아직 받지 못한 수익만, page·size로 나눈다. 출연에 없는 상태(SCHEDULED)만 고르면 빈 결과")
    void defaultAndPaging() throws Exception {
        Long processing = pendingAndProcessing();

        mockMvc.perform(internal(get(EARNINGS).param("userId", GUEST.toString()).param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                // 확인한 출연이 상태 기준 시각(확인 시각)이 가장 최근이라 먼저다
                .andExpect(jsonPath("$.data.content[0].sourceId").value(processing))
                .andExpect(jsonPath("$.data.page.size").value(1))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(2));
        mockMvc.perform(internal(get(EARNINGS).param("userId", GUEST.toString()).param("status", "SCHEDULED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("출연 기록이 없는 회원은 0원 요약·빈 목록")
    void unknownMember() throws Exception {
        mockMvc.perform(internal(get(SUMMARY).param("userId", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationPending.count").value(0))
                .andExpect(jsonPath("$.data.failed.amountHp").value(0));
        mockMvc.perform(internal(get(EARNINGS).param("userId", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    @DisplayName("userId가 없거나 1 미만, status·earningType이 계약에 없는 값이면 400 INVALID_REQUEST")
    void invalidRequest() throws Exception {
        mockMvc.perform(internal(get(SUMMARY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
        mockMvc.perform(internal(get(EARNINGS).param("userId", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(EARNINGS).param("userId", GUEST.toString()).param("status", "PAID")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        // 다른 모듈의 종류(강의 LECTURE_SALE)도 출연 계약에 없는 값이다
        mockMvc.perform(internal(get(EARNINGS).param("userId", GUEST.toString()).param("earningType", "LECTURE_SALE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("earningType"));
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인만으로도 401")
    void keyRequired() throws Exception {
        mockMvc.perform(get(SUMMARY).param("userId", GUEST.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(EARNINGS).param("userId", GUEST.toString())
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(EARNINGS).param("userId", GUEST.toString()).with(TestLogin.as(GUEST, Role.INFLUENCER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부 키로는 회원용 출연 API를 부를 수 없다")
    void keyIsNotLogin() throws Exception {
        mockMvc.perform(internal(get("/api/v1/appearances").param("role", "GUEST")))
                .andExpect(status().isUnauthorized());
    }
}
