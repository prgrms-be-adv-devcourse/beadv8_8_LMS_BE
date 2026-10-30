package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.support.AppearanceIntegrationTest;

/**
 * 기존 회원 Backfill Facade의 메서드 보안 (#394, #463). E2E는 URL 규칙({@code /admin/**})이 먼저 403을 내므로,
 * URL 규칙이 바뀌어도 Facade의 {@code @PreAuthorize}가 따로 막는지 직접 부른다.
 */
@AppearanceIntegrationTest
class AppearanceUserBackfillFacadeTest {

    @Autowired
    AppearanceUserBackfillFacade appearanceUserBackfillFacade;

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("관리자가 아니면 Backfill이 막힌다 (@PreAuthorize)")
    void notAdmin() {
        assertThatThrownBy(() -> appearanceUserBackfillFacade.backfill(0, 100))
                .isInstanceOf(AccessDeniedException.class);
    }
}
