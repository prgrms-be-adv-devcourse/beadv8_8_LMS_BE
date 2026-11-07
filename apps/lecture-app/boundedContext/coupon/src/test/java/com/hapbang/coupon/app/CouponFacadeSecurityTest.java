package com.hapbang.coupon.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.dto.CouponCreateCommand;

// Facade의 역할 검사(@PreAuthorize) 대표 몇 개. 메서드 보안만 켠 작은 컨텍스트에서 UseCase는 대역이다
@SpringJUnitConfig(CouponFacadeSecurityTest.Config.class)
class CouponFacadeSecurityTest {

    @Autowired
    private CouponSellerFacade sellerFacade;

    @Autowired
    private CouponAdminFacade adminFacade;

    @Autowired
    private CouponIssueFacade issueFacade;

    @MockitoBean
    private CouponCreateUseCase couponCreateUseCase;
    @MockitoBean
    private CouponReviewUseCase couponReviewUseCase;
    @MockitoBean
    private CouponOpenUseCase couponOpenUseCase;
    @MockitoBean
    private CouponOperateUseCase couponOperateUseCase;
    @MockitoBean
    private CouponIssueRequestUseCase couponIssueRequestUseCase;
    @MockitoBean
    private CouponIssueStatusQuery couponIssueStatusQuery;
    @MockitoBean
    private MemberCouponQuery memberCouponQuery;

    private static final CouponCreateCommand COMMAND = new CouponCreateCommand(null, null, null, 0, null, 0, null,
            null, null, null, null, false);

    @Test
    @WithMockUser(roles = "USER")
    void 일반_회원은_판매자_쿠폰을_신청할_수_없다() {
        assertThatThrownBy(() -> sellerFacade.create(1L, COMMAND)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(couponCreateUseCase);
    }

    @Test
    @WithMockUser(roles = "VERIFIED_INFLUENCER")
    void 인증된_인플루언서는_판매자_쿠폰을_신청하지만_관리자_API는_쓸_수_없다() {
        sellerFacade.create(1L, COMMAND);

        verify(couponCreateUseCase).createSeller(1L, COMMAND);
        assertThatThrownBy(() -> adminFacade.open(1L, 1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(couponOpenUseCase);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_회원_발급_접수를_할_수_없다() {
        assertThatThrownBy(() -> issueFacade.requestIssue(1L, 1L, "req-1")).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(couponIssueRequestUseCase);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import({CouponSellerFacade.class, CouponAdminFacade.class, CouponIssueFacade.class})
    static class Config {
    }
}
