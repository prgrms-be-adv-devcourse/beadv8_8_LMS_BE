package com.hapbang.lecture.in;

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
import com.hapbang.lecture.app.LectureService;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.app.dto.PageResponse;
import com.hapbang.lecture.in.dto.ContentAccessResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.LectureContentRequest;
import com.hapbang.lecture.in.dto.LectureContentsReplaceRequest;
import com.hapbang.lecture.in.dto.LectureInfoUpdateRequest;
import com.hapbang.lecture.in.dto.LectureRegisterRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 강의 API (설계 8.1) */
// TODO: 5단계(/access)에서 구현한다.
// TODO(security): 역할별 인가(설계 9장)는 global SecurityConfig와 함께 추가한다. 목록 조회는 비회원도 허용할 예정이다.
@Tag(name = "강의", description = "강의 등록·수정·삭제·조회, 콘텐츠 시청 URL")
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureController {

    private final LectureService lectureService;

    @Operation(summary = "강의 목록",
            description = "판매 중(ON_SALE)인 강의만. 제목 검색, 정렬, 페이징. 비회원도 조회할 수 있다(인가 추가 예정).")
    @GetMapping
    public PageResponse<LectureSummaryResponse> getLectures(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sort", defaultValue = "LATEST") LectureSort sort,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return lectureService.getLectures(keyword, sort, page, size);
    }

    @Operation(summary = "강의 상세",
            description = "콘텐츠 목록(제목·순서·종류)을 포함하고 파일 URL은 없다. "
                    + "판매자·관리자는 검수 대기·반려·삭제된 강의도, 보유 중인 구매자는 삭제된 강의도 조회한다.")
    @GetMapping("/{lectureId}")
    public LectureDetailResponse getLecture(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId) {
        return lectureService.getLecture(userId, lectureId);
    }

    @Operation(summary = "강의 등록",
            description = "인증된 인플루언서만. 파일을 먼저 올리고 강의와 콘텐츠를 한 번에 등록한다. 검수 대기(PENDING_INSPECTION)로 시작한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse register(@LoginUserId Long userId,
            @Valid @RequestBody LectureRegisterRequest request) {
        Long lectureId = lectureService.register(userId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId(), request.contents().stream().map(LectureContentRequest::toSpec).toList());
        return new IdResponse(lectureId);
    }

    @Operation(summary = "강의 정보 수정",
            description = "판매자 본인. 제목·소개·가격·썸네일. 재검수 대상이 아니며 삭제 전이면 어느 상태에서든 수정할 수 있다.")
    @PatchMapping("/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInfo(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureInfoUpdateRequest request) {
        lectureService.updateInfo(userId, lectureId, request.title(), request.description(), request.priceHp(),
                request.thumbnailFileId());
    }

    @Operation(summary = "강의 삭제",
            description = "판매자 본인. Soft Delete. 목록·신규 판매에서 빠지고, 기존 구매자는 계속 시청하며 지급도 그대로 진행된다.")
    @DeleteMapping("/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureService.delete(userId, lectureId);
    }

    @Operation(summary = "콘텐츠 목록 교체",
            description = "판매자 본인. 반려(REJECTED)된 강의만. 목록 전체를 교체하며 상태는 반려 그대로다. "
                    + "판매 중인 강의는 삭제 후 다시 등록한다.")
    @PutMapping("/{lectureId}/contents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceContents(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId,
            @Valid @RequestBody LectureContentsReplaceRequest request) {
        lectureService.replaceContents(userId, lectureId,
                request.contents().stream().map(LectureContentRequest::toSpec).toList());
    }

    @Operation(summary = "재검수 신청", description = "판매자 본인. 반려(REJECTED) → 검수 대기(PENDING_INSPECTION).")
    @PostMapping("/{lectureId}/inspection-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestInspection(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        lectureService.requestInspection(userId, lectureId);
    }

    @Operation(summary = "내가 등록한 강의", description = "인증된 인플루언서. 모든 상태(삭제 포함).")
    @GetMapping("/me")
    public PageResponse<MyLectureResponse> getMyLectures(@LoginUserId Long userId,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return lectureService.getMyLectures(userId, page, size);
    }

    @Operation(summary = "콘텐츠 시청 URL 발급",
            description = "구매자·판매자·관리자. 짧은 만료의 Presigned GET URL. "
                    + "구매자 항목이 PURCHASED이면 영상·자료 모두 거부한다(재생 동의 필요). "
                    + "구매자는 삭제되지 않은 콘텐츠만 받는다.")
    @GetMapping("/{lectureId}/contents/{contentId}/access")
    public ContentAccessResponse getContentAccess(@LoginUserId Long userId,
            @PathVariable("lectureId") Long lectureId, @PathVariable("contentId") Long contentId) {
        throw NotImplemented.yet();
    }
}
