package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.app.LectureFacade;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.in.dto.ContentAccessResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.LectureContentRequest;
import com.hapbang.lecture.in.dto.LectureContentsReplaceRequest;
import com.hapbang.lecture.in.dto.LectureInfoUpdateRequest;
import com.hapbang.lecture.in.dto.LectureRegisterRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 API (설계 8.1). Swagger 명세는 {@link ApiV1LectureControllerDocs} */
// TODO: 5단계(/access)에서 구현한다.
// 역할 검사(설계 9장): 목록은 비회원도 허용(SecurityConfig), 상세는 회원, 등록·수정·삭제·재검수 신청은 인증된 인플루언서(LectureFacade, D16).
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureController implements ApiV1LectureControllerDocs {

    private final LectureFacade lectureFacade;

    @Override
    @GetMapping
    public Page<LectureSummaryResponse> getLectures(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "LATEST") LectureSort sort,
            Pageable pageable) {
        return lectureFacade.getLectures(keyword, sort, pageable);
    }

    @Override
    @GetMapping("/{lectureId}")
    public LectureDetailResponse getLecture(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId) {
        return lectureFacade.getLecture(userId, lectureId);
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse register(@LoginUserId Long userId,
            @Valid @RequestBody LectureRegisterRequest request) {
        Long lectureId = lectureFacade.register(userId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId(), request.contents().stream().map(LectureContentRequest::toSpec).toList());
        return new IdResponse(lectureId);
    }

    @Override
    @PatchMapping("/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInfo(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureInfoUpdateRequest request) {
        lectureFacade.updateInfo(userId, lectureId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId());
    }

    @Override
    @DeleteMapping("/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureFacade.delete(userId, lectureId);
    }

    @Override
    @PutMapping("/{lectureId}/contents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceContents(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureContentsReplaceRequest request) {
        lectureFacade.replaceContents(userId, lectureId,
                request.contents().stream().map(LectureContentRequest::toSpec).toList());
    }

    @Override
    @PostMapping("/{lectureId}/inspection-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestInspection(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureFacade.requestInspection(userId, lectureId);
    }

    @Override
    @GetMapping("/me")
    public Page<MyLectureResponse> getMyLectures(@LoginUserId Long userId,
            Pageable pageable) {
        return lectureFacade.getMyLectures(userId, pageable);
    }

    @Override
    @GetMapping("/{lectureId}/contents/{contentId}/access")
    public ContentAccessResponse getContentAccess(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId, @PathVariable("contentId") Long contentId) {
        throw NotImplemented.yet();
    }
}
