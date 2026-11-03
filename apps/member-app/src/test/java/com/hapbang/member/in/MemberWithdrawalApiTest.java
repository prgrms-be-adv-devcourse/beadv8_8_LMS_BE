package com.hapbang.member.in;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.WithdrawalBlockedException;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

/**
 * 회원 탈퇴 API (#593). 오케스트레이터는 대역으로 두고 응답 모양(204·쿠키 삭제, 409·이유 모음, 503)을 확인한다.
 * 오케스트레이터 동작은 MemberWithdrawalUseCaseTest, 스위치가 꺼졌을 때 404는 ApiV1MemberControllerTest가 확인한다
 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class MemberWithdrawalApiTest {

    private static final long USER_ID = 42L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MemberWithdrawalUseCase memberWithdrawalUseCase;

    @Test
    @DisplayName("탈퇴하면 204이고 refresh token 쿠키를 지운다")
    void withdraw() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(RefreshTokenCookies.NAME + "=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        verify(memberWithdrawalUseCase).withdraw(USER_ID);
    }

    @Test
    @DisplayName("막히면 409 WITHDRAWAL_BLOCKED와 모든 앱의 막힌 이유를 한 번에 준다")
    void blocked() throws Exception {
        willThrow(new WithdrawalBlockedException(List.of(
                WithdrawalBlockReasonDto.of("BILLING_CHARGE_BALANCE_REMAINING", "충전 HP 12,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요."),
                new WithdrawalBlockReasonDto("LECTURE_ORDER_HOLDING", "구매한 강의 2건의 구매 확정 기간이 끝나지 않았어요.",
                        LocalDate.of(2026, 11, 20)))))
                .given(memberWithdrawalUseCase).withdraw(USER_ID);

        mockMvc.perform(delete("/api/v1/members/me").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_BLOCKED"))
                .andExpect(jsonPath("$.detail").value("탈퇴하기 전에 정리할 항목이 있어요."))
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0].code").value("BILLING_CHARGE_BALANCE_REMAINING"))
                .andExpect(jsonPath("$.reasons[0].message").value("충전 HP 12,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요."))
                .andExpect(jsonPath("$.reasons[0].availableAt").doesNotExist())
                .andExpect(jsonPath("$.reasons[1].availableAt").value("2026-11-20"));
    }

    @Test
    @DisplayName("이미 처리 중이면 409 WITHDRAWAL_IN_PROGRESS와 다시 시도할 시간을 알려 준다")
    void inProgress() throws Exception {
        willThrow(new MemberException(MemberErrorCode.WITHDRAWAL_IN_PROGRESS))
                .given(memberWithdrawalUseCase).withdraw(USER_ID);

        mockMvc.perform(delete("/api/v1/members/me").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_IN_PROGRESS"))
                .andExpect(jsonPath("$.detail").value(containsString("2분쯤 뒤에 다시 시도해 주세요")));
    }

    @Test
    @DisplayName("다른 앱을 부를 수 없으면 503 WITHDRAWAL_UNAVAILABLE이고 쿠키는 지우지 않는다")
    void unavailable() throws Exception {
        willThrow(new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE))
                .given(memberWithdrawalUseCase).withdraw(USER_ID);

        mockMvc.perform(delete("/api/v1/members/me").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_UNAVAILABLE"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me"))
                .andExpect(status().isUnauthorized());
    }
}
