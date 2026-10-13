package com.hapbang.customersupport.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.in.dto.CreateInquiryRequest;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * 고객센터 문의 REST API Swagger 명세. 매핑은 {@link ApiV1InquiryController}
 * <p>
 * 파라미터 검증(@Valid 등)은 여기에 둔다. 구현 메서드에서 제약을 새로 정의하면 Bean Validation 규칙 위반으로 요청이 실패한다.
 */
@Tag(name = "고객센터 문의", description = """
        회원의 1:1 문의 접수, 내 문의 조회·삭제(유저·인플루언서·인증된 인플루언서).
        관리자는 `/api/v1/inquiries/admin` 아래에서 모든 문의를 조회한다.""")
public interface ApiV1InquirySpec {

    int KEYWORD_MAX_LENGTH = 100;

    @Operation(summary = "문의 접수", description = "새 문의를 접수한다(201). 접수한 문의는 답변 대기(WAITING) 상태다.")
    RsData<InquiryResponse> createInquiry(Long userId, @Valid CreateInquiryRequest request);

    @Operation(summary = "내 문의 목록", description = """
            내가 접수한 문의를 조회한다. `page`(0부터), `size`(기본 20, 최대 100)로 나눠 받는다.
            `keyword`를 보내면 제목이나 내용에 검색어가 들어간 문의만 찾는다(대소문자 무시).
            정렬은 `sort=LATEST`(최신순, 기본)|`OLDEST`(오래된순)로 정하고, 필드 이름 정렬(`sort=title,asc`)은 쓰지 않는다.""")
    RsData<Page<InquirySummaryResponse>> getMyInquiries(Long userId,
            @Parameter(description = "검색어 (제목·내용, 최대 " + KEYWORD_MAX_LENGTH + "자). 없으면 전체")
            @Size(max = KEYWORD_MAX_LENGTH, message = "검색어는 " + KEYWORD_MAX_LENGTH + "자 이하여야 합니다.") String keyword,
            @Parameter(description = "정렬. LATEST(최신순, 기본) | OLDEST(오래된순)") InquirySort sort,
            Pageable pageable);

    @Operation(summary = "내 문의 상세", description = "내가 접수한 문의만 볼 수 있다. 다른 회원의 문의는 404(INQUIRY_NOT_FOUND).")
    RsData<InquiryResponse> getMyInquiry(Long userId, Long inquiryId);

    @Operation(summary = "내 문의 삭제", description = """
            답변 대기(WAITING)인 내 문의만 삭제할 수 있다(204). 답변 완료면 409(INQUIRY_NOT_DELETABLE).
            다른 회원의 문의나 이미 삭제된 문의는 404(INQUIRY_NOT_FOUND). 본문은 분쟁 확인용으로 남는다(Soft Delete).""")
    ResponseEntity<Void> deleteInquiry(Long userId, Long inquiryId);

    @Operation(summary = "문의 목록 (관리자)", description = """
            모든 회원의 문의를 조회한다. 삭제된 문의는 빠진다. `status`가 없으면 전체.
            정렬 기본값은 오래된순(OLDEST)이다. 먼저 들어온 답변 대기 문의부터 처리하기 위해서다.""")
    RsData<Page<InquirySummaryResponse>> getInquiries(
            @Parameter(description = "상태. WAITING(답변 대기) | ANSWERED(답변 완료). 없으면 전체") InquiryStatus status,
            @Parameter(description = "정렬. OLDEST(오래된순, 기본) | LATEST(최신순)") InquirySort sort,
            Pageable pageable);

    @Operation(summary = "문의 상세 (관리자)", description = "모든 회원의 문의를 조회한다. 삭제된 문의는 404(INQUIRY_NOT_FOUND).")
    RsData<InquiryResponse> getInquiry(Long inquiryId);
}
