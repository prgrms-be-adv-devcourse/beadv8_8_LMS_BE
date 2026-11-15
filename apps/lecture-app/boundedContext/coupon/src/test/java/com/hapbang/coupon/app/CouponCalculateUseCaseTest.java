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
import com.hapbang.coupon.config.CouponPlatformFundingProperties;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.Selection;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

// 계산 서비스의 흐름(강의 재확인, 매핑별 후보 조회, 응답 변환)을 본다. 금액·중첩·추천 규칙은 계산기 도메인 테스트가 본다
class CouponCalculateUseCaseTest {

    private static final long SELLER_COUPON = 11L;

    private final MemberCouponRepository memberCouponRepository = mock();
    private static final long PLATFORM_COUPON = 12L;

    private final LectureCatalog lectureCatalog = mock();
    private CouponCalculateUseCase useCase;
    private CouponUsageSupport usageSupport;
    private CouponSupport couponSupport;

    @BeforeEach
    void setUp() {
        usageSupport = support(memberCouponRepository, mock(ProcessedOperationRepository.class), transactionManager());
        couponSupport = new CouponSupport(mock(CouponRepository.class), lectureCatalog, mock(CouponIssueStore.class));
        // 비활성 설정의 기존 거절 경로도 검증한다
        useCase = new CouponCalculateUseCase(usageSupport, couponSupport, new CouponPlatformFundingProperties(false),
                CLOCK);
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

    // 플랫폼 재원 비활성 ---------------------------------------------------------------------------------------

    @Test
    void 플랫폼_재원이_꺼져_있으면_추천과_미리보기_추천에서_플랫폼_쿠폰을_뺀다() {
        givenSellerAndPlatformAvailable();

        CouponCalculateResponse calculated = useCase.calculate(request(null));
        CouponCalculateResponse quoted = useCase.quote(MEMBER_ID, List.of(LECTURE_ID), null);

        for (CouponCalculateResponse response : List.of(calculated, quoted)) {
            assertThat(response.items().getFirst().coupons()).extracting(CouponCalculateResponse.Coupon::memberCouponId)
                    .containsExactly(SELLER_COUPON);
            assertThat(response.items().getFirst().platformDiscount()).isZero();
        }
    }

    @Test
    void 플랫폼_재원이_꺼져_있는데_플랫폼_쿠폰을_직접_고르면_409_COUPON_NOT_USABLE이다() {
        givenOwnedByIds(memberCouponRepository, owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L)),
                owned(PLATFORM_COUPON, MEMBER_ID, platformAnyAmountCoupon(2L)));

        assertUsageError(() -> useCase.calculate(request(List.of(
                        new CouponCalculateRequest.Selection(SELLER_COUPON, LECTURE_ID),
                        new CouponCalculateRequest.Selection(PLATFORM_COUPON, LECTURE_ID)))),
                CouponErrorCode.COUPON_NOT_USABLE, PLATFORM_COUPON);
        assertThatThrownBy(() -> useCase.quote(MEMBER_ID, List.of(LECTURE_ID),
                List.of(new Selection(PLATFORM_COUPON, LECTURE_ID))))
                .hasMessage(CouponCalculateUseCase.PLATFORM_FUNDING_DISABLED);
    }

    @Test
    void 플랫폼_재원이_꺼져_있어도_다른_회원의_플랫폼_쿠폰을_고르면_소유_검사의_거절이라_쿠폰_종류가_드러나지_않는다() {
        givenOwnedByIds(memberCouponRepository, owned(PLATFORM_COUPON, MEMBER_ID + 1, platformAnyAmountCoupon(2L)));

        assertThatThrownBy(() -> useCase.calculate(request(List.of(
                new CouponCalculateRequest.Selection(PLATFORM_COUPON, LECTURE_ID)))))
                .isInstanceOfSatisfying(CouponUsageException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_USABLE);
                    assertThat(exception.getMessage()).isNotEqualTo(CouponCalculateUseCase.PLATFORM_FUNDING_DISABLED);
                });
    }

    @Test
    void 플랫폼_재원이_켜져_있으면_플랫폼_쿠폰도_추천하고_직접_고를_수_있다() {
        useCase = new CouponCalculateUseCase(usageSupport, couponSupport, CouponPlatformFundingProperties.defaults(),
                CLOCK);
        givenSellerAndPlatformAvailable();
        givenOwnedByIds(memberCouponRepository, owned(PLATFORM_COUPON, MEMBER_ID, platformAnyAmountCoupon(2L)));

        CouponCalculateResponse recommended = useCase.calculate(request(null));
        CouponCalculateResponse selected = useCase.calculate(
                request(List.of(new CouponCalculateRequest.Selection(PLATFORM_COUPON, LECTURE_ID))));

        assertThat(recommended.items().getFirst().coupons()).extracting(CouponCalculateResponse.Coupon::memberCouponId)
                .containsExactly(SELLER_COUPON, PLATFORM_COUPON);
        assertThat(selected.items().getFirst().platformDiscount()).isEqualTo(1_500);
    }

    private void givenSellerAndPlatformAvailable() {
        when(memberCouponRepository.findOwnedByStatus(MEMBER_ID, MemberCouponStatus.AVAILABLE))
                .thenReturn(List.of(owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L)),
                        owned(PLATFORM_COUPON, MEMBER_ID, platformAnyAmountCoupon(2L))));
    }

    // 회원용 미리보기 -------------------------------------------------------------------------------------------

    @Test
    void 미리보기는_강의_조회로_가격과_판매자를_채워_추천하고_매핑을_주면_그_매핑만_계산한다() {
        when(memberCouponRepository.findOwnedByStatus(MEMBER_ID, MemberCouponStatus.AVAILABLE))
                .thenReturn(List.of(owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L))));
        givenOwnedByIds(memberCouponRepository, owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L)));

        CouponCalculateResponse recommended = useCase.quote(MEMBER_ID, List.of(LECTURE_ID), null);
        CouponCalculateResponse selected = useCase.quote(MEMBER_ID, List.of(LECTURE_ID),
                List.of(new Selection(SELLER_COUPON, LECTURE_ID)));

        assertThat(recommended.recommended()).isTrue();
        assertThat(recommended.items().getFirst().price()).isEqualTo(PRICE);
        assertThat(recommended.totalBuyerAmount()).isEqualTo(9_000);
        assertThat(selected.recommended()).isFalse();
        assertThat(selected.totalBuyerAmount()).isEqualTo(9_000);
        verify(lectureCatalog, times(2)).findLecture(LECTURE_ID);
    }

    @Test
    void 미리보기에서_강의_앱에_닿지_못하면_조회_불가가_나가고_상한을_넘으면_강의를_조회하지_않고_400이다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenThrow(new LectureCatalogUnavailableException("연결 실패", null));

        assertThatThrownBy(() -> useCase.quote(MEMBER_ID, List.of(LECTURE_ID), null))
                .isInstanceOf(LectureCatalogUnavailableException.class);
        clearInvocations(lectureCatalog);
        assertUsageError(() -> useCase.quote(MEMBER_ID, List.of(LECTURE_ID, LECTURE_ID), null),
                CouponErrorCode.COUPON_DUPLICATE_LECTURE, null);
        verifyNoInteractions(lectureCatalog, memberCouponRepository);
    }

    private static CouponCalculateRequest request(List<CouponCalculateRequest.Selection> selections) {
        return new CouponCalculateRequest(MEMBER_ID,
                List.of(new CouponCalculateRequest.Item(LECTURE_ID, SELLER_ID, PRICE)), selections);
    }
}
