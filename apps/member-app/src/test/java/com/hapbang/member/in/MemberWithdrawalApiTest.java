package com.hapbang.member.in;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.WithdrawalBlockedException;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.RestoreTicketStore;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import jakarta.servlet.http.Cookie;

/**
 * 회원 탈퇴·복구 API (#593). 오케스트레이터와 복구 티켓 저장소는 대역으로 두고 응답 모양(204·쿠키 삭제, 409·이유 모음, 503,
 * 복구 티켓 쿠키 발급·복구 204·복구 취소·401·403)을 확인한다 (#633). 로그인 실패 횟수 등은 실제 Redis를 쓴다.
 * 오케스트레이터 동작은 MemberWithdrawalUseCaseTest, 스위치가 꺼졌을 때 404는 ApiV1MemberControllerTest가 확인한다
 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class MemberWithdrawalApiTest {

    private static final long USER_ID = 42L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MemberWithdrawalUseCase memberWithdrawalUseCase;

    @MockitoBean
    RestoreTicketStore restoreTicketStore;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EmailVerificationStore emailVerificationStore;

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

    @Test
    @DisplayName("30일 안의 탈퇴 회원이 비밀번호로 로그인하면 409이고, 복구 티켓은 본문이 아니라 쿠키로 준다 (#633)")
    void passwordLoginRestorable() throws Exception {
        String token = emailVerificationStore.issueToken("withdrawn1@example.com");
        User user = memberFacade.signUp("withdrawn1@example.com", "abcd1234", "withdrawn1", token);
        jdbcTemplate.update("update members.users set status = 'WITHDRAWN', deleted_at = now() where id = ?", user.getId());
        given(restoreTicketStore.issue(user.getId())).willReturn("ticket-1");

        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"withdrawn1@example.com\", \"password\": \"abcd1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWN_RESTORABLE"))
                .andExpect(jsonPath("$.restorableUntil").exists())
                .andExpect(jsonPath("$.restoreTicket").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith(RestoreTicketCookies.NAME + "=ticket-1;"),
                        containsString("Path=/api/v1/members/restore"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"))));
    }

    @Test
    @DisplayName("복구 티켓 쿠키로 복구하면 계정만 되살리고 204와 티켓 쿠키 삭제를 준다. 로그인(refresh token 쿠키)은 주지 않는다 (#633)")
    void restore() throws Exception {
        given(restoreTicketStore.find("ticket-1")).willReturn(Optional.of(USER_ID));

        mockMvc.perform(post("/api/v1/members/restore").cookie(restoreTicketCookie("ticket-1")))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, contains(expiredRestoreTicketCookie())));
        verify(memberWithdrawalUseCase).restore(USER_ID);
        verify(restoreTicketStore).delete("ticket-1");
    }

    @Test
    @DisplayName("복구 티켓이 만료됐으면 401 INVALID_RESTORE_TICKET이고 쓸모없는 티켓 쿠키를 지운다")
    void restoreInvalidTicket() throws Exception {
        given(restoreTicketStore.find("expired")).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/members/restore").cookie(restoreTicketCookie("expired")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_RESTORE_TICKET"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, expiredRestoreTicketCookie()));
    }

    @Test
    @DisplayName("복구 기한이 지났으면 403 LOGIN_NOT_ALLOWED이고 다시 시도해도 안 되므로 티켓 쿠키를 지운다")
    void restoreExpiredPeriod() throws Exception {
        given(restoreTicketStore.find("ticket-1")).willReturn(Optional.of(USER_ID));
        willThrow(new MemberException(MemberErrorCode.LOGIN_NOT_ALLOWED)).given(memberWithdrawalUseCase).restore(USER_ID);

        mockMvc.perform(post("/api/v1/members/restore").cookie(restoreTicketCookie("ticket-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOGIN_NOT_ALLOWED"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, expiredRestoreTicketCookie()));
        verify(restoreTicketStore, never()).delete("ticket-1");
    }

    @Test
    @DisplayName("지갑을 열지 못해 복구하지 못하면 503 RESTORE_UNAVAILABLE이고 티켓과 쿠키를 남겨 다시 시도할 수 있다")
    void restoreUnavailable() throws Exception {
        given(restoreTicketStore.find("ticket-1")).willReturn(Optional.of(USER_ID));
        willThrow(new MemberException(MemberErrorCode.RESTORE_UNAVAILABLE)).given(memberWithdrawalUseCase).restore(USER_ID);

        mockMvc.perform(post("/api/v1/members/restore").cookie(restoreTicketCookie("ticket-1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESTORE_UNAVAILABLE"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verify(restoreTicketStore, never()).delete("ticket-1");
    }

    @Test
    @DisplayName("복구 취소는 로그인 없이 부르고, 복구 티켓을 지우고 티켓 쿠키도 지운다 (#633)")
    void cancelRestore() throws Exception {
        mockMvc.perform(delete("/api/v1/members/restore").cookie(restoreTicketCookie("ticket-1")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, expiredRestoreTicketCookie()));
        verify(restoreTicketStore).delete("ticket-1");
    }

    @Test
    @DisplayName("복구 티켓 쿠키 없이 복구를 취소해도 204이고 지울 티켓이 없다")
    void cancelRestoreWithoutCookie() throws Exception {
        mockMvc.perform(delete("/api/v1/members/restore"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, expiredRestoreTicketCookie()));
        verify(restoreTicketStore, never()).delete(any());
    }

    @Test
    @DisplayName("복구 티켓 쿠키가 없으면 401 INVALID_RESTORE_TICKET (본문에 티켓을 넣어도 읽지 않는다)")
    void restoreWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/v1/members/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restoreTicket\": \"ticket-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_RESTORE_TICKET"));
        verify(restoreTicketStore, never()).find("ticket-1");
    }

    private static Cookie restoreTicketCookie(String ticket) {
        return new Cookie(RestoreTicketCookies.NAME, ticket);
    }

    private static Matcher<String> expiredRestoreTicketCookie() {
        return allOf(startsWith(RestoreTicketCookies.NAME + "=;"), containsString("Max-Age=0"),
                containsString("Path=/api/v1/members/restore"));
    }
}
