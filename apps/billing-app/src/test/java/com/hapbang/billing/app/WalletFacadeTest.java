package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 내 지갑 조회의 역할 검사(@PreAuthorize)와 가입 이벤트 경로의 무검사 호출을 Facade 단위로 검증한다.
@IntegrationTest
class WalletFacadeTest {

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    WalletRepository walletRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자가 내 지갑을 조회하면 거절하고 지갑을 만들지 않는다")
    void getMyWallet_admin() {
        assertThatThrownBy(() -> walletFacade.getMyWallet(1L))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(walletRepository.findByUserId(1L)).isEmpty();
    }

    @Test
    @DisplayName("가입 이벤트 경로는 로그인 정보 없이도 지갑을 만든다")
    void getOrCreateWallet_withoutLogin() {
        assertThat(walletFacade.getOrCreateWallet(1L).getUserId()).isEqualTo(1L);
    }
}
