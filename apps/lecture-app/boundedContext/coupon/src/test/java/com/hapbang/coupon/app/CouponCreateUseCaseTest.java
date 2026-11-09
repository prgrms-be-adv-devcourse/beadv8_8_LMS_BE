package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.config.CouponPolicyProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;

// 판매자 쿠폰 신청에서 강의 조회 결과(없음·다른 판매자·판매 상태·가격 snapshot)를 어떻게 쓰는지 본다. 발행 검증은 도메인 테스트가 본다
class CouponCreateUseCaseTest {

    private final CouponRepository couponRepository = mock();
    private final LectureCatalog lectureCatalog = mock();
    private CouponCreateUseCase useCase;

    @BeforeEach
    void setUp() {
        CouponSupport support = new CouponSupport(couponRepository, lectureCatalog, mock(CouponIssueStore.class));
        useCase = new CouponCreateUseCase(couponRepository, support, new CouponPolicyProperties(null), CLOCK);
        when(couponRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    private static CouponCreateCommand lectureCommand() {
        return new CouponCreateCommand(COUPON_NAME, CouponTargetType.LECTURE, LECTURE_ID, DiscountType.RATE, 10, null, 100,
                ISSUE_START, ISSUE_END, ExpiryType.RELATIVE, 30, null, false);
    }

    @Test
    void 판매자_쿠폰은_조회한_강의_가격을_snapshot해_DRAFT로_저장한다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, 12_000L, "ON_SALE")));

        var view = useCase.createSeller(SELLER_ID, lectureCommand());

        assertThat(view.couponId()).isEqualTo(COUPON_ID);
        assertThat(view.status()).isEqualTo(CouponStatus.DRAFT);
        assertThat(view.basePrice()).isEqualTo(12_000L);
        assertThat(view.applicantId()).isEqualTo(SELLER_ID);
    }

    @Test
    void 다른_판매자의_강의면_403이고_저장하지_않는다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, OTHER_MEMBER_ID, 12_000L, "ON_SALE")));

        assertCouponError(() -> useCase.createSeller(SELLER_ID, lectureCommand()), CouponErrorCode.NOT_LECTURE_SELLER);

        verify(couponRepository, never()).save(any(Coupon.class));
    }

    @Test
    void 강의가_없으면_404다() {
        when(lectureCatalog.findLecture(LECTURE_ID)).thenReturn(Optional.empty());

        assertCouponError(() -> useCase.createPlatform(ADMIN_ID, lectureCommand()), CouponErrorCode.LECTURE_NOT_FOUND);

        verify(couponRepository, never()).save(any(Coupon.class));
    }

    @Test
    void 판매_중이_아닌_강의면_판매자_신청과_플랫폼_발행_모두_409이고_저장하지_않는다() {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, 12_000L, "PENDING_INSPECTION")));

        assertNotOnSale(() -> useCase.createSeller(SELLER_ID, lectureCommand()));
        assertNotOnSale(() -> useCase.createPlatform(ADMIN_ID, lectureCommand()));

        verify(couponRepository, never()).save(any(Coupon.class));
    }

    @Test
    void 설정한_만료_상한을_도메인에_넘긴다() {
        // 명령의 만료는 접수 후 30일이다
        CouponCreateUseCase shortLimit = new CouponCreateUseCase(couponRepository,
                new CouponSupport(couponRepository, lectureCatalog, mock(CouponIssueStore.class)),
                new CouponPolicyProperties(29), CLOCK);
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, 12_000L, "ON_SALE")));

        assertThatThrownBy(() -> shortLimit.createSeller(SELLER_ID, lectureCommand()))
                .isInstanceOfSatisfying(CouponDomainException.class,
                        e -> assertThat(e.getError()).isEqualTo(CouponDomainError.EXPIRY_TOO_LONG));
        verify(couponRepository, never()).save(any(Coupon.class));
    }

    static void assertNotOnSale(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CouponDomainException.class,
                e -> assertThat(e.getError()).isEqualTo(CouponDomainError.LECTURE_NOT_ON_SALE));
    }
}
