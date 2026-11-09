package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

// 계산 서비스의 흐름(강의 재확인, 매핑별 후보 조회, 응답 변환)을 본다. 금액·중첩·추천 규칙은 계산기 도메인 테스트가 본다
class CouponCalculateUseCaseTest {

    private static final long SELLER_COUPON = 11L;

    private final MemberCouponRepository memberCouponRepository = mock();
    private final LectureCatalog lectureCatalog = mock();
    private CouponCalculateUseCase useCase;

    @BeforeEach
    void setUp() {
        CouponUsageSupport usageSupport = support(memberCouponRepository, mock(ProcessedOperationRepository.class),
                transactionManager());
        CouponSupport couponSupport = new CouponSupport(mock(CouponRepository.class), lectureCatalog,
                mock(CouponIssueStore.class));
        useCase = new CouponCalculateUseCase(usageSupport, couponSupport, CLOCK);
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, PRICE, "ON_SALE")));
    }

    @Test
    void 매핑을_생략하면_회원의_AVAILABLE_쿠폰으로_추천하고_계산_근거를_돌려준다() {
        when(memberCouponRepository.findOwnedByStatus(MEMBER_ID, MemberCouponStatus.AVAILABLE))
                .thenReturn(List.of(owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L))));

        CouponCalculateResponse response = useCase.calculate(request(null));

        assertThat(response.recommended()).isTrue();
        assertThat(response.optimal()).isTrue();
        assertThat(response.totalDiscount()).isEqualTo(1_000);
        assertThat(response.totalBuyerAmount()).isEqualTo(9_000);
        CouponCalculateResponse.Item item = response.items().getFirst();
        assertThat(item.sellerDiscount()).isEqualTo(1_000);
        assertThat(item.sellerPayout()).isEqualTo(9_000);
        assertThat(item.coupons()).singleElement().satisfies(coupon -> {
            assertThat(coupon.memberCouponId()).isEqualTo(SELLER_COUPON);
            assertThat(coupon.couponType()).isEqualTo("SELLER");
            assertThat(coupon.applyOrder()).isEqualTo(1);
            assertThat(coupon.baseAmount()).isEqualTo(PRICE);
            assertThat(coupon.expectedGeneration()).isZero();
            assertThat(coupon.policyVersion()).isEqualTo(1);
        });
    }

    @Test
    void 매핑이_있으면_매핑한_발급_쿠폰만_읽어_계산한다() {
        givenOwnedByIds(memberCouponRepository, owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L)));

        CouponCalculateResponse response = useCase.calculate(
                request(List.of(new CouponCalculateRequest.Selection(SELLER_COUPON, LECTURE_ID))));

        assertThat(response.recommended()).isFalse();
        assertThat(response.totalDiscount()).isEqualTo(1_000);
        verify(memberCouponRepository).findOwnedByIds(argThat(ids -> ids.size() == 1 && ids.contains(SELLER_COUPON)));
        verify(memberCouponRepository, never()).findOwnedByStatus(anyLong(), any());
    }

    @Test
    void 매핑이_빈_목록이면_쿠폰을_읽지_않고_할인_0이다() {
        CouponCalculateResponse response = useCase.calculate(request(List.of()));

        assertThat(response.totalDiscount()).isZero();
        assertThat(response.totalBuyerAmount()).isEqualTo(PRICE);
        assertThat(response.items().getFirst().coupons()).isEmpty();
        verifyNoInteractions(memberCouponRepository);
    }

    @Test
    void 강의_가격이_요청과_다르면_409이다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, PRICE + 1, "ON_SALE")));

        assertUsageError(() -> useCase.calculate(request(null)), CouponErrorCode.COUPON_LECTURE_PRICE_MISMATCH, null);
        verifyNoInteractions(memberCouponRepository);
    }

    @Test
    void 강의_앱에_닿지_못하면_조회_불가가_그대로_나간다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenThrow(new LectureCatalogUnavailableException("연결 실패", null));

        assertThatThrownBy(() -> useCase.calculate(request(null)))
                .isInstanceOf(LectureCatalogUnavailableException.class);
        verifyNoInteractions(memberCouponRepository);
    }

    private static CouponCalculateRequest request(List<CouponCalculateRequest.Selection> selections) {
        return new CouponCalculateRequest(MEMBER_ID,
                List.of(new CouponCalculateRequest.Item(LECTURE_ID, SELLER_ID, PRICE)), selections);
    }
}
