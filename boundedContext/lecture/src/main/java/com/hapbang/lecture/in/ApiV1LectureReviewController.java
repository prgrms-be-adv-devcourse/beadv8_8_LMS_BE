package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.ReviewCreateRequest;
import com.hapbang.lecture.in.dto.ReviewResponse;
import com.hapbang.lecture.in.dto.ReviewUpdateRequest;

import jakarta.validation.Valid;

/** 강의 리뷰 API (설계 8.6). Swagger 명세는 {@link ApiV1LectureReviewControllerDocs} */
// TODO: 13장 6단계에서 구현한다.
// TODO(security): 리뷰 작성·수정·삭제는 관리자 불가(설계 9장). global SecurityConfig와 함께 추가한다.
@RestController
@RequestMapping("/api/v1/lectures")
public class ApiV1LectureReviewController implements ApiV1LectureReviewControllerDocs {

    @Override
    @GetMapping("/{lectureId}/reviews")
    public Page<ReviewResponse> getReviews(@PathVariable("lectureId") Long lectureId,
            Pageable pageable) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/{lectureId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse create(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody ReviewCreateRequest request) {
        throw NotImplemented.yet();
    }

    @Override
    @PatchMapping("/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId,
            @Valid @RequestBody ReviewUpdateRequest request) {
        throw NotImplemented.yet();
    }

    @Override
    @DeleteMapping("/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginUserId Long userId, @PathVariable("reviewId") Long reviewId) {
        throw NotImplemented.yet();
    }
}
