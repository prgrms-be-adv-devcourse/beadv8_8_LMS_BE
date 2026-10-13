package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.dto.ContentAccessResponse;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.in.dto.IdResponse;
import com.hapbang.lecture.in.dto.LectureContentsReplaceRequest;
import com.hapbang.lecture.in.dto.LectureInfoUpdateRequest;
import com.hapbang.lecture.in.dto.LectureRegisterRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 강의 API Swagger 명세 (설계 8.1). 매핑은 {@link ApiV1LectureController}
 * <p>
 * 성공 응답은 {@link RsData}의 data에 담고, 본문 없는 응답은 204다 (D29).
 */
@Tag(name = "강의", description = "강의 등록·수정·삭제·조회, 콘텐츠 시청 URL")
public interface ApiV1LectureSpec {

    @Operation(summary = "강의 목록",
            description = "판매 중(ON_SALE)인 강의만. 비회원도 조회할 수 있다. 제목 검색, 정렬, 페이징(page·size, 최대 100). "
                    + "정렬은 sort=LATEST|PRICE_ASC|PRICE_DESC|RATING으로 정하고, 필드 이름 정렬(sort=priceHp,asc)은 쓰지 않는다.")
    RsData<Page<LectureSummaryResponse>> getLectures(String keyword,
                                             @Parameter(description = """
                                                     강의 목록 정렬
                                                     - `LATEST`: 최신순(기본)
                                                     - `PRICE_ASC`: 가격 낮은순
                                                     - `PRICE_DESC`: 가격 높은순
                                                     - `RATING`: 평점순. 리뷰 기능 전까지는 최신순으로 정렬된다""")
                                             LectureSort sort,
                                             Pageable pageable);

    @Operation(summary = "강의 상세",
            description = "콘텐츠 목록(제목·순서·종류)을 포함하고 파일 URL은 없다. "
                    + "판매자 본인은 검수 대기·반려·삭제된 강의도 조회한다. "
                    + "관리자는 GET /api/v1/lectures/admin/{lectureId}로 조회한다. "
                    + "보유 중인 구매자의 삭제된 강의 조회는 추가 예정이다.")
    RsData<LectureDetailResponse> getLecture(Long userId, Long lectureId);

    @Operation(summary = "강의 등록",
            description = "인증된 인플루언서만. 파일을 먼저 올리고 강의와 콘텐츠를 한 번에 등록한다. 검수 대기(PENDING_INSPECTION)로 시작한다.")
    RsData<IdResponse> register(Long userId, LectureRegisterRequest request);

    @Operation(summary = "강의 정보 수정",
            description = "판매자 본인. 제목·소개·가격·썸네일. 재검수 대상이 아니며 삭제 전이면 어느 상태에서든 수정할 수 있다(204).")
    ResponseEntity<Void> updateInfo(Long userId, Long lectureId, LectureInfoUpdateRequest request);

    @Operation(summary = "강의 삭제",
            description = "판매자 본인. Soft Delete. 목록·신규 판매에서 빠지고, 기존 구매자는 계속 시청하며 지급도 그대로 진행된다(204).")
    ResponseEntity<Void> delete(Long userId, Long lectureId);

    @Operation(summary = "콘텐츠 목록 교체",
            description = "판매자 본인. 반려(REJECTED)된 강의만. 목록 전체를 교체하며 상태는 반려 그대로다. "
                    + "판매 중인 강의는 삭제 후 다시 등록한다(204).")
    ResponseEntity<Void> replaceContents(Long userId, Long lectureId, LectureContentsReplaceRequest request);

    @Operation(summary = "재검수 신청", description = "판매자 본인. 반려(REJECTED) → 검수 대기(PENDING_INSPECTION) (204).")
    ResponseEntity<Void> requestInspection(Long userId, Long lectureId);

    @Operation(summary = "내가 등록한 강의", description = "인증된 인플루언서. 모든 상태(삭제 포함).")
    RsData<Page<MyLectureResponse>> getMyLectures(Long userId, Pageable pageable);

    @Operation(summary = "콘텐츠 시청 URL 발급",
            description = "판매자 본인·관리자(검수용). 상태와 관계없이(삭제된 강의 포함) 현재 콘텐츠의 S3 Presigned GET URL을 "
                    + "1시간 만료로 발급한다. 영상은 브라우저에서 재생되고, 자료(PDF)는 원본 파일명으로 내려받는다"
                    + "(Content-Disposition: attachment). 만료 뒤에는 다시 발급받는다. "
                    + "오류: 없는 강의 404 LECTURE_NOT_FOUND, 판매자·관리자가 아니면 판매 중이 아닌 강의는 404 LECTURE_NOT_FOUND이고 "
                    + "판매 중인 강의는 403 CONTENT_ACCESS_DENIED, 이 강의에 없거나 교체된 콘텐츠는 404 LECTURE_CONTENT_NOT_FOUND. "
                    + "구매자 시청(재생 동의 포함)은 주문 구현 후 추가 예정이다.")
    RsData<ContentAccessResponse> getContentAccess(Long userId, Long lectureId, Long contentId);
}
