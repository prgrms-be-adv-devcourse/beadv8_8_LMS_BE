package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.testsupport.IntegrationTest;

/**
 * Facade를 직접 불러 운영과 같은 조건(OSIV 없음, 웹 요청 밖)에서 쓰기 응답을 검증한다.
 * 응답 DTO는 UseCase 커밋 뒤 트랜잭션 밖에서 만들어지므로, 새 행의 ID·작성 시각이 담겨야 한다.
 */
@IntegrationTest
class InquiryFacadeTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private InquiryFacade inquiryFacade;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 접수_응답에_ID와_작성_시각이_담긴다() {
        loginAs("USER");

        InquiryResponse created = inquiryFacade.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");

        assertThat(created.inquiryId()).isNotNull();
        assertThat(created.createdAt()).isNotNull();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
