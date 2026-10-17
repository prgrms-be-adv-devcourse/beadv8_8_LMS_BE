package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.LectureReviewFacade;
import com.hapbang.lecture.app.dto.ReviewResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.ReviewCreateRequest;
import com.hapbang.lecture.in.dto.ReviewUpdateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 리뷰 API (설계 8.6). Swagger 명세는 {@link ApiV1LectureReviewSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
// 역할 검사(작성·수정·삭제는 관리자 불가)는 LectureReviewFacade의 @PreAuthorize가 한다.
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureReviewController implements ApiV1LectureReviewSpec {

    private final LectureReviewFacade lectureReviewFacade;

    @Override
    @GetMapping("/{lectureId}/reviews")
    public RsData<Page<ReviewResponse>> getReviews(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            Pageable pageable) {
        return RsData.ok("리뷰 목록을 조회했습니다.", lectureReviewFacade.getReviews(userId, lectureId, pageable));
    }

    @Override
    @PostMapping("/{lectureId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<IdResponse> create(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody ReviewCreateRequest request) {
        Long reviewId = lectureReviewFacade.write(userId, lectureId, request.orderItemId(), request.rating(),
                request.content());
        return RsData.created("리뷰를 작성했습니다.", new IdResponse(reviewId));
    }

    @Override
    @PatchMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> update(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId,
            @Valid @RequestBody ReviewUpdateRequest request) {
        lectureReviewFacade.edit(userId, reviewId, request.rating(), request.content());
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId) {
        lectureReviewFacade.delete(userId, reviewId);
        return ResponseEntity.noContent().build();
    }
}
