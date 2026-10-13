package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hapbang.customersupport.app.dto.InquiryReplyResponse;
import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.testsupport.IntegrationTest;

/**
 * Facade를 직접 불러 운영과 같은 조건(OSIV 없음, 웹 요청 밖)에서 쓰기 응답을 검증한다.
 * 응답 DTO는 UseCase 커밋 뒤 트랜잭션 밖에서 만들어지므로, 새 행의 ID·작성 시각이 담기고 답변 목록의 지연 로딩 오류가 없어야 한다.
 */
@IntegrationTest
class InquiryFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;

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
        assertThat(created.replies()).isEmpty();
    }

    @Test
    void 답변_응답에_기존_답변과_새_답변의_ID와_작성_시각이_담긴다() {
        loginAs("USER");
        Long inquiryId = inquiryFacade.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").inquiryId();
        loginAs("ADMIN");
        inquiryFacade.reply(inquiryId, ADMIN_ID, "첫 답변");

        InquiryResponse replied = inquiryFacade.reply(inquiryId, ADMIN_ID, "추가 답변");

        assertThat(replied.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(replied.replies()).extracting(InquiryReplyResponse::content).containsExactly("첫 답변", "추가 답변");
        assertThat(replied.replies()).allSatisfy(reply -> {
            assertThat(reply.replyId()).isNotNull();
            assertThat(reply.createdAt()).isNotNull();
        });
    }

    @Test
    void 답변_수정_응답에_답변_목록이_담긴다() {
        loginAs("USER");
        Long inquiryId = inquiryFacade.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").inquiryId();
        loginAs("ADMIN");
        Long replyId = inquiryFacade.reply(inquiryId, ADMIN_ID, "첫 답변").replies().getFirst().replyId();

        InquiryResponse edited = inquiryFacade.editReply(inquiryId, replyId, "고친 답변");

        assertThat(edited.replies()).extracting(InquiryReplyResponse::content).containsExactly("고친 답변");
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
