package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static com.hapbang.lecture.fixture.PostgresErrorFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.CartRepository;

// 장바구니 담기 저장 중 무결성 위반 중 같은 강의 중복만 409로 바꾸는지 DB 없이 검증한다.
// 실제 동시 담기는 CartAddItemUseCaseTest가 맡는다.
class CartAddItemViolationTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long LECTURE_ID = 10L;

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final LectureSupport lectureSupport = mock(LectureSupport.class);
    private final CartAddItemUseCase useCase = new CartAddItemUseCase(cartRepository, lectureSupport);

    @BeforeEach
    void setUp() {
        given(lectureSupport.findLecture(LECTURE_ID)).willReturn(withId(onSaleLecture(), LECTURE_ID));
        given(cartRepository.findByUserId(BUYER_ID)).willReturn(Optional.of(Cart.create(BUYER_ID, NOW)));
    }

    @Test
    void 같은_강의_부분_유일_인덱스_위반은_409_ALREADY_IN_CART다() {
        willThrow(violation(UNIQUE_VIOLATION, "uk_cart_item_cart_lecture")).given(cartRepository).flush();

        assertThatThrownBy(() -> useCase.addItem(BUYER_ID, LECTURE_ID))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.ALREADY_IN_CART));
    }

    @Test
    void 그_밖의_무결성_위반은_그대로_던져_500이_되게_한다() {
        DataIntegrityViolationException notNull = violation("23502", null);
        willThrow(notNull).given(cartRepository).flush();

        assertThatThrownBy(() -> useCase.addItem(BUYER_ID, LECTURE_ID)).isSameAs(notNull);
    }
}
