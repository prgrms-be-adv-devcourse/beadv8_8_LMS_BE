package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.dto.ReviewResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.ReviewCreateRequest;
import com.hapbang.lecture.in.dto.ReviewUpdateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 리뷰 API Swagger 명세 (설계 8.6). 매핑은 {@link ApiV1LectureReviewController} */
@Tag(name = "강의 리뷰", description = "리뷰 작성·수정·삭제, 평점")
public interface ApiV1LectureReviewSpec {

    @Operation(summary = "리뷰 목록",
            description = "최신순, 페이징(page·size). 반환된 항목의 리뷰와 삭제된 리뷰는 빠진다. "
                    + "평균 평점·리뷰 수는 강의 목록·상세에서 준다. "
                    + "판매 중이 아닌(삭제된) 강의는 강의 상세와 같이 판매자·보유 구매자만 조회하고, 그 밖에는 404 LECTURE_NOT_FOUND.")
    RsData<Page<ReviewResponse>> getReviews(Long userId, Long lectureId, Pageable pageable);

    @Operation(summary = "리뷰 작성",
            description = "구매 확정(CONFIRMED)·지급(PAID_OUT)된 본인 항목만(관리자 불가). 한 회원은 한 강의에 리뷰를 하나만 유지한다(삭제 후 재작성 가능). "
                    + "구매 확정 전·취소·반환된 항목은 409 ORDER_ITEM_NOT_REVIEWABLE, 남의 항목이나 이 강의의 항목이 아니면 404 ORDER_ITEM_NOT_FOUND, "
                    + "이미 쓴 강의는 409 ALREADY_REVIEWED. 반환된 항목에 남은 내 리뷰는 다시 산 항목으로 쓴 새 리뷰로 대체된다.")
    RsData<IdResponse> create(Long userId, Long lectureId, ReviewCreateRequest request);

    @Operation(summary = "리뷰 수정",
            description = "작성자 본인(204, 관리자 불가). 평점·본문을 모두 바꾼다. 남의 리뷰는 403 NOT_REVIEW_WRITER, 없거나 삭제된 리뷰는 404 REVIEW_NOT_FOUND.")
    ResponseEntity<Void> update(Long userId, Long reviewId, ReviewUpdateRequest request);

    @Operation(summary = "리뷰 삭제",
            description = "작성자 본인. Soft Delete(204, 관리자 불가). 남의 리뷰는 403 NOT_REVIEW_WRITER, 없거나 삭제된 리뷰는 404 REVIEW_NOT_FOUND.")
    ResponseEntity<Void> delete(Long userId, Long reviewId);
}
