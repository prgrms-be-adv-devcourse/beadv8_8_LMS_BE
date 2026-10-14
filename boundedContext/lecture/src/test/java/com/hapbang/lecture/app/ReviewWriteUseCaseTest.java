package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.out.LectureReviewRepository;

// 동시 작성으로 생긴 DB 무결성 위반 중 리뷰 중복만 409로 바꾸는지 DB 없이 검증한다.
// 순차 중복은 기존 리뷰 조회로 먼저 걸러지므로 이 경로는 API 테스트로 재현할 수 없다.
class ReviewWriteUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long LECTURE_ID = 10L;
    private static final Long ORDER_ITEM_ID = 100L;

    private final LectureReviewRepository lectureReviewRepository = mock(LectureReviewRepository.class);
    private final LectureSupport lectureSupport = mock(LectureSupport.class);
    private final LectureOrderSupport lectureOrderSupport = mock(LectureOrderSupport.class);
    private ReviewWriteUseCase useCase;

    @BeforeEach
    void setUp() {
        Lecture lecture = withId(onSaleLecture(), LECTURE_ID);
        given(lectureSupport.findLecture(LECTURE_ID)).willReturn(lecture);
        given(lectureOrderSupport.findItem(BUYER_ID, ORDER_ITEM_ID)).willReturn(confirmedItem(lecture));
        given(lectureReviewRepository.findAlive(LECTURE_ID, BUYER_ID)).willReturn(Optional.empty());
        useCase = new ReviewWriteUseCase(lectureReviewRepository, lectureSupport, lectureOrderSupport,
                Clock.fixed(NOW.atZone(LectureConfig.ZONE).toInstant(), LectureConfig.ZONE));
    }

    @Test
    void 동시에_쓴_리뷰가_유일_인덱스에_걸리면_409_ALREADY_REVIEWED다() {
        given(lectureReviewRepository.save(any())).willThrow(violation("23505", "uk_lecture_review_lecture_reviewer"));

        assertThatThrownBy(() -> useCase.write(BUYER_ID, LECTURE_ID, ORDER_ITEM_ID, 5, null))
                .isInstanceOfSatisfying(LectureException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.ALREADY_REVIEWED));
    }

    @Test
    void 리뷰_중복이_아닌_무결성_위반은_그대로_던져_500이_되게_한다() {
        DataIntegrityViolationException check = violation("23514", "ck_lecture_review_rating");
        given(lectureReviewRepository.save(any())).willThrow(check);

        assertThatThrownBy(() -> useCase.write(BUYER_ID, LECTURE_ID, ORDER_ITEM_ID, 5, null)).isSameAs(check);
    }

    @Test
    void 리뷰_유일_인덱스_두_개의_UNIQUE_위반만_중복으로_본다() {
        assertThat(ReviewWriteUseCase.isDuplicateReview(violation("23505", "uk_lecture_review_order_item"))).isTrue();
        assertThat(ReviewWriteUseCase.isDuplicateReview(violation("23505", "uk_lecture_review_lecture_reviewer")))
                .isTrue();
        assertThat(ReviewWriteUseCase.isDuplicateReview(violation("23505", "pk_lecture_review"))).isFalse();
        assertThat(ReviewWriteUseCase.isDuplicateReview(violation("23514", "uk_lecture_review_order_item"))).isFalse();
        assertThat(ReviewWriteUseCase.isDuplicateReview(violation("23505", null))).isFalse();
    }

    @Test
    void Hibernate가_메시지에서_뽑은_제약_이름만_있으면_중복으로_보지_않는다() {
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "constraint violation", new SQLException("violation", "23505"), "uk_lecture_review_order_item");

        assertThat(ReviewWriteUseCase.isDuplicateReview(
                new DataIntegrityViolationException("constraint violation", hibernate))).isFalse();
    }

    private static OrderItem confirmedItem(Lecture lecture) {
        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(lecture), Set.of(),
                LocalDateTime.of(2026, 9, 30, 10, 0));
        OrderItem item = order.getItems().getFirst();
        ReflectionTestUtils.setField(item, "id", ORDER_ITEM_ID);
        ReflectionTestUtils.setField(item, "status", OrderItemStatus.CONFIRMED);
        return item;
    }

    /** Hibernate 예외 안에 PostgreSQL 드라이버 예외가 들어 있는, 실제와 같은 원인 체인 (billing WalletCreateUseCaseTest와 같음) */
    private static DataIntegrityViolationException violation(String sqlState, String constraintName) {
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement", postgresError(sqlState, constraintName), null);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    /** 한국어 메시지와 구조화된 SQLState·제약 이름을 가진 PostgreSQL 드라이버 예외 */
    private static SQLException postgresError(String sqlState, String constraintName) {
        // 드라이버는 compileOnly라 테스트 컴파일 경로에 없어 실행 경로(test-support runtimeOnly)의 클래스로 만든다.
        // 서버 오류 형식: 필드 타입 문자 + 값 + NUL (S: 심각도, C: SQLState, M: 메시지, n: 제약 이름)
        String fields = "SERROR\0C" + sqlState + "\0M중복된 키 값이 고유 제약 조건을 위반함\0"
                + (constraintName == null ? "" : "n" + constraintName + "\0");
        try {
            Class<?> serverErrorType = Class.forName("org.postgresql.util.ServerErrorMessage");
            Object serverError = serverErrorType.getConstructor(String.class).newInstance(fields);
            return (SQLException) Class.forName("org.postgresql.util.PSQLException")
                    .getConstructor(serverErrorType).newInstance(serverError);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("PostgreSQL 드라이버 예외를 만들 수 없습니다.", exception);
        }
    }
}
