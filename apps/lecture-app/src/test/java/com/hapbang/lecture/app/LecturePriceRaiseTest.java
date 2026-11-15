package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.fake.FakeCouponApiClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 가격 인상 제한: 가격을 올릴 때만 트랜잭션 밖에서 쿠폰의 가격 인상 제한을 묻고, 막혔거나 확인할 수 없으면 바꾸지 않는다(막는 쪽).
 * 쿠폰 Client의 상태 변환은 CouponApiClientTest가, 가격 비교 규칙은 LectureTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "VERIFIED_INFLUENCER")
@ExtendWith(OutputCaptureExtension.class)
class LecturePriceRaiseTest {

    private static final long RAISED = PRICE_HP + 5_000L;

    @Autowired
    private LectureFacade lectureFacade;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeCouponApiClient coupon;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        coupon.reset();
    }

    @Test
    void 가격을_올리면_트랜잭션_밖에서_가격_인상_제한을_묻고_막히지_않았으면_바꾼다() {
        Long lectureId = saveOnSale();

        updatePrice(lectureId, RAISED);

        assertThat(coupon.calls()).singleElement().satisfies(call -> {
            assertThat(call.request()).isEqualTo(lectureId);
            assertThat(call.transactionActive()).isFalse();
        });
        assertThat(price(lectureId)).isEqualTo(RAISED);
    }

    @Test
    void 플랫폼_쿠폰이_걸린_강의는_409_LECTURE_PRICE_RAISE_BLOCKED이고_가격이_그대로다() {
        Long lectureId = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(new CouponPriceRaiseBlockedResponse(lectureId, true)));

        assertThatThrownBy(() -> updatePrice(lectureId, RAISED))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_PRICE_RAISE_BLOCKED);
        assertThat(price(lectureId)).isEqualTo(PRICE_HP);
    }

    @Test
    void 가격_인상_제한을_확인할_수_없으면_막는_쪽으로_503이고_가격이_그대로다(CapturedOutput output) {
        Long lectureId = saveOnSale();
        List<CouponCallResult<?>> failures = List.of(new CouponCallResult.NotSent<>("회로 열림"),
                new CouponCallResult.Unknown<>(0, "응답 시간 초과"), new CouponCallResult.Rejected<>(401, null, null, null),
                new CouponCallResult.Rejected<>(400, "INVALID_REQUEST", null, null));
        failures.forEach(failure -> coupon.respond(request -> failure));

        for (int i = 0; i < failures.size(); i++) {
            assertThatThrownBy(() -> updatePrice(lectureId, RAISED))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_PRICE_RAISE_UNAVAILABLE);
        }
        assertThat(price(lectureId)).isEqualTo(PRICE_HP);
        // 쿠폰의 4xx(401·403 제외) 거절은 계약·설정 문제라 alert 표식을 단 ERROR로 남긴다
        assertThat(output.getAll().lines().filter(line -> line.contains("[LECTURE_PRICE_RAISE_CHECK_FAILED]")))
                .singleElement().satisfies(line -> assertThat(line).contains(" ERROR ", "lectureId=" + lectureId,
                        "httpStatus=400", "couponCode=INVALID_REQUEST"));
    }

    @Test
    void 가격을_내리거나_그대로_두면_쿠폰을_부르지_않는다() {
        Long lectureId = saveOnSale();

        updatePrice(lectureId, PRICE_HP);
        updatePrice(lectureId, PRICE_HP - 5_000L);

        assertThat(coupon.calls()).isEmpty();
        assertThat(price(lectureId)).isEqualTo(PRICE_HP - 5_000L);
    }

    @Test
    void 가격_인상_제한을_확인하는_사이_가격이_바뀌면_409_LECTURE_PRICE_CHANGED다() {
        Long lectureId = saveOnSale();
        coupon.respond(request -> {
            // 확인하는 사이 다른 요청이 가격을 바꿨다
            jdbcTemplate.update("update lectures.lecture set price_hp = ? where id = ?", PRICE_HP + 1_000L, lectureId);
            return new CouponCallResult.Succeeded<>(new CouponPriceRaiseBlockedResponse(lectureId, false));
        });

        assertThatThrownBy(() -> updatePrice(lectureId, RAISED))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_PRICE_CHANGED);
        assertThat(price(lectureId)).isEqualTo(PRICE_HP + 1_000L);
    }

    @Test
    void 판매자가_아니면_쿠폰을_부르기_전에_거부한다() {
        Long lectureId = saveOnSale();

        assertThatThrownBy(() -> lectureFacade.updateInfo(OTHER_USER_ID, lectureId, TITLE, DESCRIPTION, RAISED, null))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_LECTURE_SELLER);
        assertThat(coupon.calls()).isEmpty();
    }

    private void updatePrice(Long lectureId, long priceHp) {
        lectureFacade.updateInfo(SELLER_ID, lectureId, TITLE, DESCRIPTION, priceHp, null);
    }

    private long price(Long lectureId) {
        return jdbcTemplate.queryForObject("select price_hp from lectures.lecture where id = ?", Long.class,
                lectureId);
    }

    private Long saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture).getId();
    }
}
