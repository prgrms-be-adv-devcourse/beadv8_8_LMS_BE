package com.hapbang.member.in;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.member.app.MemberMaskingUseCase;

/** 마스킹 배치 (#594): 묶음이 가득 차면 이어서 처리하고, 덜 차면(남은 대상 없음·실패) 멈춘다 */
class MemberMaskingBatchTest {

    private final MemberMaskingUseCase useCase = mock(MemberMaskingUseCase.class);
    private final MemberMaskingBatch batch = new MemberMaskingBatch(useCase);

    @Test
    @DisplayName("묶음을 가득 채워 마스킹하면 다음 묶음을 이어서 처리한다")
    void continuesWhileFull() {
        given(useCase.maskDue(MemberMaskingBatch.BATCH_SIZE))
                .willReturn(MemberMaskingBatch.BATCH_SIZE, MemberMaskingBatch.BATCH_SIZE, 3);

        batch.run();

        verify(useCase, times(3)).maskDue(MemberMaskingBatch.BATCH_SIZE);
    }

    @Test
    @DisplayName("대상이 없으면 한 번만 확인한다")
    void stopsWhenNothingDue() {
        given(useCase.maskDue(MemberMaskingBatch.BATCH_SIZE)).willReturn(0);

        batch.run();

        verify(useCase, times(1)).maskDue(MemberMaskingBatch.BATCH_SIZE);
    }
}
