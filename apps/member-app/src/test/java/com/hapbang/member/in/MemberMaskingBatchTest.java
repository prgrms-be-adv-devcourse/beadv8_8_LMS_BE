package com.hapbang.member.in;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.member.app.MemberMaskingUseCase;

/** 마스킹 배치 (#594): 기한이 지난 회원 전부를 정한 묶음 크기로 처리하게 맡긴다 */
class MemberMaskingBatchTest {

    @Test
    @DisplayName("묶음 크기를 넘겨 기한이 지난 회원을 모두 마스킹한다")
    void masksAllDue() {
        MemberMaskingUseCase useCase = mock(MemberMaskingUseCase.class);

        new MemberMaskingBatch(useCase).run();

        verify(useCase).maskAllDue(MemberMaskingBatch.PAGE_SIZE);
    }
}
