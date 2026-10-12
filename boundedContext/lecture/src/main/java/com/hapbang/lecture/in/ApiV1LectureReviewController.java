package com.hapbang.lecture.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.app.dto.PageResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.ReviewCreateRequest;
import com.hapbang.lecture.in.dto.ReviewResponse;
import com.hapbang.lecture.in.dto.ReviewUpdateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 강의 리뷰 API (설계 8.6) */
// TODO: 13장 6단계에서 구현한다.
// TODO(security): 리뷰 작성·수정·삭제는 관리자 불가(설계 9장). global SecurityConfig와 함께 추가한다.
@Tag(name = "강의 리뷰", description = "리뷰 작성·수정·삭제, 평점")
@RestController
@RequestMapping("/api/v1/lectures")
public class ApiV1LectureReviewController {

    @Operation(summary = "리뷰 목록", description = "최신순. 평균 평점·리뷰 수는 강의 목록·상세에서 준다.")
    @GetMapping("/{lectureId}/reviews")
    public PageResponse<ReviewResponse> getReviews(@PathVariable("lectureId") Long lectureId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "리뷰 작성",
            description = "구매 확정(CONFIRMED)·지급(PAID_OUT)된 본인 항목만. 한 회원은 한 강의에 리뷰를 하나만 유지한다(삭제 후 재작성 가능).")
    @PostMapping("/{lectureId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse create(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody ReviewCreateRequest request) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "리뷰 수정", description = "작성자 본인.")
    @PatchMapping("/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId,
            @Valid @RequestBody ReviewUpdateRequest request) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "리뷰 삭제", description = "작성자 본인. Soft Delete.")
    @DeleteMapping("/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId) {
        throw NotImplemented.yet();
    }
}
