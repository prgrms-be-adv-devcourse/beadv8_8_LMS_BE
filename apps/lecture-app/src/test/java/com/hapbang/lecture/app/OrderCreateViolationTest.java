package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static com.hapbang.lecture.fixture.PostgresErrorFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;

// 주문 저장 중 무결성 위반 중 보유 강의 중복만 409로 바꾸는지 DB 없이 검증한다(서버 메시지가 한국어여도 같아야 한다).
// 실제 동시 주문은 OrderCreateUseCaseTest가 맡는다.
class OrderCreateViolationTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long LECTURE_ID = 10L;

    private final LectureRepository lectureRepository = mock(LectureRepository.class);
    private final LectureOrderRepository lectureOrderRepository = mock(LectureOrderRepository.class);
    private final OrderCreateUseCase useCase = new OrderCreateUseCase(lectureRepository, lectureOrderRepository,
            mock(LectureHpTaskRepository.class), mock(LectureHpTaskSupport.class), mock(LectureHpTaskExecutor.class),
            mock(LectureOrderSupport.class), new LectureOrderProperties(null),
            Clock.fixed(NOW.atZone(LectureConfig.ZONE).toInstant(), LectureConfig.ZONE));

    @BeforeEach
    void setUp() {
        given(lectureRepository.findAllById(List.of(LECTURE_ID))).willReturn(List.of(withId(onSaleLecture(), LECTURE_ID)));
    }

    @Test
    void 보유_강의_부분_유일_인덱스_위반은_409_ALREADY_OWNED_LECTURE다() {
        given(lectureOrderRepository.saveAndFlush(any()))
                .willThrow(violation(UNIQUE_VIOLATION, "uk_order_item_buyer_lecture_owned"));

        assertThatThrownBy(() -> useCase.place(BUYER_ID, List.of(LECTURE_ID)))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.ALREADY_OWNED_LECTURE));
    }

    @Test
    void 그_밖의_무결성_위반은_그대로_던져_500이_되게_한다() {
        DataIntegrityViolationException check = violation(CHECK_VIOLATION, "ck_order_item_price");
        given(lectureOrderRepository.saveAndFlush(any())).willThrow(check);

        assertThatThrownBy(() -> useCase.place(BUYER_ID, List.of(LECTURE_ID))).isSameAs(check);
    }
}
