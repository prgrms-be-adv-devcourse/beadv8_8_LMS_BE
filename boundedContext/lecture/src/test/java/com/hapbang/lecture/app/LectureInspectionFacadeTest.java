package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.testsupport.IntegrationTest;

/** HTTP로는 /admin 경로 검사(SecurityConfig)가 먼저 막으므로 Facade의 역할 검사는 여기서 확인한다. */
@IntegrationTest
class LectureInspectionFacadeTest {

    @Autowired
    private LectureInspectionFacade lectureInspectionFacade;

    @Test
    @WithMockUser(roles = "VERIFIED_INFLUENCER")
    void 관리자가_아니면_검수_목록_승인_반려를_할_수_없다() {
        assertThatThrownBy(() -> lectureInspectionFacade.getInspections(LectureStatus.PENDING_INSPECTION,
                PageRequest.of(0, 10)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> lectureInspectionFacade.approve(1L, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> lectureInspectionFacade.reject(1L, 1L, "반려 사유"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
