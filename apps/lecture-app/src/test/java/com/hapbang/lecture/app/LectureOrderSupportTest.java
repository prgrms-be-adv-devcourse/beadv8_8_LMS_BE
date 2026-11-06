package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;

// 회원 입력(주문 항목 ID)으로 주문 행을 잠글 때는 본인 항목인지 먼저 확인한다 (#712 리뷰). 남의 주문에 잠금을 걸어 연결을 잡거나
// 응답 시간으로 남의 항목이 있는지 짐작하지 못하게 한다
class LectureOrderSupportTest {

    private static final Long BUYER_ID = 2L;
    private static final Long ORDER_ITEM_ID = 10L;

    private final LectureOrderRepository lectureOrderRepository = mock(LectureOrderRepository.class);
    private final LectureOrderSupport support = new LectureOrderSupport(lectureOrderRepository,
            mock(LectureHpTaskRepository.class));

    @Test
    void 남의_항목이나_없는_항목이면_주문을_잠그지_않고_ORDER_ITEM_NOT_FOUND다() {
        given(lectureOrderRepository.existsItemOfBuyer(ORDER_ITEM_ID, BUYER_ID)).willReturn(false);

        assertThatThrownBy(() -> support.lockOrderOfBuyerItem(BUYER_ID, ORDER_ITEM_ID))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.ORDER_ITEM_NOT_FOUND);
        verify(lectureOrderRepository, never()).findForUpdateById(any());
    }

    @Test
    void 본인_항목이면_그_주문을_잠근다() {
        given(lectureOrderRepository.existsItemOfBuyer(ORDER_ITEM_ID, BUYER_ID)).willReturn(true);
        given(lectureOrderRepository.findOrderIdByItemId(ORDER_ITEM_ID)).willReturn(Optional.of(7L));

        support.lockOrderOfBuyerItem(BUYER_ID, ORDER_ITEM_ID);

        verify(lectureOrderRepository).findForUpdateById(7L);
    }
}
