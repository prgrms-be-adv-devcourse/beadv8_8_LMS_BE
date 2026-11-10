package com.hapbang.coupon.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.out.CouponUsageRepository;

// 사용 이력 조회 서비스: 상태 필터를 그대로 넘기고, 요청의 정렬은 버리고(저장소 쿼리 순서로 고정) 페이지 크기는 100으로 자른다.
// 본인 행·순서·조인·count는 저장소 테스트, 변환은 CouponUsageViewTest가 본다
class CouponUsageHistoryQueryTest {

    private static final long MEMBER_ID = 100L;

    private final CouponUsageRepository couponUsageRepository = mock();
    private final CouponUsageHistoryQuery query = new CouponUsageHistoryQuery(couponUsageRepository);

    @Test
    void 상태를_그대로_넘기고_정렬은_버리며_페이지_크기는_100으로_자른다() {
        when(couponUsageRepository.findMyUsages(anyLong(), any(), any())).thenReturn(Page.empty());

        query.myUsages(MEMBER_ID, CouponUsageStatus.PAID, PageRequest.of(2, 500, Sort.by("couponId")));

        verify(couponUsageRepository).findMyUsages(MEMBER_ID, CouponUsageStatus.PAID, PageRequest.of(2, 100));
    }

    @Test
    void 상태가_없으면_전체로_넘기고_100_이하_크기는_그대로다() {
        when(couponUsageRepository.findMyUsages(anyLong(), any(), any())).thenReturn(Page.empty());

        query.myUsages(MEMBER_ID, null, PageRequest.of(0, 20));

        verify(couponUsageRepository).findMyUsages(eq(MEMBER_ID), eq(null), eq(PageRequest.of(0, 20)));
    }
}
