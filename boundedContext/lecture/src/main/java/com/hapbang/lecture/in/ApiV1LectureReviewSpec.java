package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.ReviewCreateRequest;
import com.hapbang.lecture.in.dto.ReviewResponse;
import com.hapbang.lecture.in.dto.ReviewUpdateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 리뷰 API Swagger 명세 (설계 8.6). 매핑은 {@link ApiV1LectureReviewController} */
@Tag(name = "강의 리뷰", description = "리뷰 작성·수정·삭제, 평점")
public interface ApiV1LectureReviewSpec {

    @Operation(summary = "리뷰 목록", description = "최신순. 평균 평점·리뷰 수는 강의 목록·상세에서 준다.")
    Page<ReviewResponse> getReviews(Long lectureId, Pageable pageable);

    @Operation(summary = "리뷰 작성",
            description = "구매 확정(CONFIRMED)·지급(PAID_OUT)된 본인 항목만. 한 회원은 한 강의에 리뷰를 하나만 유지한다(삭제 후 재작성 가능).")
    IdResponse create(Long userId, Long lectureId, ReviewCreateRequest request);

    @Operation(summary = "리뷰 수정", description = "작성자 본인.")
    void update(Long userId, Long reviewId, ReviewUpdateRequest request);

    @Operation(summary = "리뷰 삭제", description = "작성자 본인. Soft Delete.")
    void delete(Long userId, Long reviewId);
}
