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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.global.auth.LoginUserId;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.LectureFacade;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.app.dto.ContentAccessResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.LectureContentRequest;
import com.hapbang.lecture.in.dto.LectureContentsReplaceRequest;
import com.hapbang.lecture.in.dto.LectureInfoUpdateRequest;
import com.hapbang.lecture.in.dto.LectureRegisterRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 강의 API (설계 8.1). Swagger 명세는 {@link ApiV1LectureSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
// 역할 검사(설계 9장): 목록은 비회원도 허용(SecurityConfig), 상세는 회원, 등록·수정·삭제·재검수 신청은 인증된 인플루언서(LectureFacade, D16),
// 콘텐츠 시청 URL은 판매자 본인·관리자(LectureContentAccessSupport, D26).
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureController implements ApiV1LectureSpec {

    private final LectureFacade lectureFacade;

    @Override
    @GetMapping
    public RsData<Page<LectureSummaryResponse>> getLectures(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "LATEST") LectureSort sort,
            Pageable pageable) {
        return RsData.ok("강의 목록을 조회했습니다.", lectureFacade.getLectures(keyword, sort, pageable));
    }

    @Override
    @GetMapping("/{lectureId}")
    public RsData<LectureDetailResponse> getLecture(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId) {
        return RsData.ok("강의를 조회했습니다.", lectureFacade.getLecture(userId, lectureId));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<IdResponse> register(@LoginUserId Long userId,
            @Valid @RequestBody LectureRegisterRequest request) {
        Long lectureId = lectureFacade.register(userId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId(), request.contents().stream().map(LectureContentRequest::toSpec).toList());
        return RsData.created("강의를 등록했습니다.", new IdResponse(lectureId));
    }

    @Override
    @PatchMapping("/{lectureId}")
    public ResponseEntity<Void> updateInfo(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureInfoUpdateRequest request) {
        lectureFacade.updateInfo(userId, lectureId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId());
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/{lectureId}")
    public ResponseEntity<Void> delete(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureFacade.delete(userId, lectureId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PutMapping("/{lectureId}/contents")
    public ResponseEntity<Void> replaceContents(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureContentsReplaceRequest request) {
        lectureFacade.replaceContents(userId, lectureId,
                request.contents().stream().map(LectureContentRequest::toSpec).toList());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PostMapping("/{lectureId}/inspection-requests")
    public ResponseEntity<Void> requestInspection(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureFacade.requestInspection(userId, lectureId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @GetMapping("/me")
    public RsData<Page<MyLectureResponse>> getMyLectures(@LoginUserId Long userId,
            Pageable pageable) {
        return RsData.ok("내 강의 목록을 조회했습니다.", lectureFacade.getMyLectures(userId, pageable));
    }

    @Override
    @GetMapping("/{lectureId}/contents/{contentId}/access")
    public RsData<ContentAccessResponse> getContentAccess(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId, @PathVariable("contentId") Long contentId) {
        return RsData.ok("시청 URL을 발급했습니다.", lectureFacade.getContentAccess(userId, lectureId, contentId));
    }
}
