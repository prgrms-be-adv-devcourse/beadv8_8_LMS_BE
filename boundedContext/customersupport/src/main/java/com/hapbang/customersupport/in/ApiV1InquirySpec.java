package com.hapbang.customersupport.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.in.dto.CreateInquiryRequest;
import com.hapbang.customersupport.in.dto.InquiryReplyRequest;
import com.hapbang.customersupport.in.dto.ObjectionResolutionRequest;
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
        관리자는 `/api/v1/inquiries/admin` 아래에서 모든 문의를 조회하고 답변한다. 추가 문의는 새 문의로 접수한다.""")
public interface ApiV1InquirySpec {

    int KEYWORD_MAX_LENGTH = 100;

    @Operation(summary = "문의 접수", description = """
            새 문의를 접수한다(201). 접수한 문의는 답변 대기(WAITING) 상태다.
            강의 내용 다름 이의 제기(`LECTURE_CONTENT_DISPUTE`)는 `orderItemId`(강의 주문 항목 ID)가 필수이고, 접수하면 그 항목의 판매자 지급이 멈춘다.
            `orderItemId`가 없으면 400(INQUIRY_TARGET_REQUIRED), 다른 유형에 보내면 400(INQUIRY_TARGET_NOT_ALLOWED).
            접수할 수 없으면 문의가 만들어지지 않는다: 404(OBJECTION_NOT_BUYER), 409(OBJECTION_NOT_OBJECTIONABLE,
            OBJECTION_ALREADY_PAID_OUT, OBJECTION_ALREADY_FILED, OBJECTION_PERIOD_EXPIRED).""")
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

    @Operation(summary = "내 문의 상세", description = "관리자 답변을 함께 준다(오래된 답변부터). 내가 접수한 문의만 볼 수 있다. 다른 회원의 문의는 404(INQUIRY_NOT_FOUND).")
    RsData<InquiryResponse> getMyInquiry(Long userId, Long inquiryId);

    @Operation(summary = "내 문의 삭제", description = """
            답변 대기(WAITING)인 내 문의만 삭제할 수 있다(204). 답변 완료면 409(INQUIRY_NOT_DELETABLE).
            다른 회원의 문의나 이미 삭제된 문의는 404(INQUIRY_NOT_FOUND). 본문은 분쟁 확인용으로 남는다(Soft Delete).
            강의 내용 다름 이의 제기 문의를 삭제하면 이의 제기를 철회하고 판매자 지급 중단을 푼다. 같은 주문 항목으로 다시 이의 제기할 수 있다.
            강의 쪽에서 철회할 수 없으면 삭제되지 않는다: 404(OBJECTION_NOT_BUYER), 409(OBJECTION_NOT_FILED, OBJECTION_INQUIRY_MISMATCH).""")
    ResponseEntity<Void> deleteInquiry(Long userId, Long inquiryId);

    @Operation(summary = "문의 목록 (관리자)", description = """
            모든 회원의 문의를 조회한다. 삭제된 문의는 빠진다. `status`가 없으면 전체.
            정렬 기본값은 오래된순(OLDEST)이다. 먼저 들어온 답변 대기 문의부터 처리하기 위해서다.""")
    RsData<Page<InquirySummaryResponse>> getInquiries(
            @Parameter(description = "상태. WAITING(답변 대기) | ANSWERED(답변 완료). 없으면 전체") InquiryStatus status,
            @Parameter(description = "정렬. OLDEST(오래된순, 기본) | LATEST(최신순)") InquirySort sort,
            Pageable pageable);

    @Operation(summary = "문의 상세 (관리자)", description = "모든 회원의 문의를 답변과 함께 조회한다. 삭제된 문의는 404(INQUIRY_NOT_FOUND).")
    RsData<InquiryResponse> getInquiry(Long inquiryId);

    @Operation(summary = "답변 등록 (관리자)", description = """
            답변을 등록하면(201) 문의가 답변 완료(ANSWERED)가 된다. 답변은 여러 번 달 수 있다.
            회원이 삭제한 문의는 404(INQUIRY_NOT_FOUND).""")
    RsData<InquiryResponse> replyInquiry(Long userId, Long inquiryId, @Valid InquiryReplyRequest request);

    @Operation(summary = "답변 수정 (관리자)", description = """
            답변 내용을 고친다. 어느 관리자든 고칠 수 있다. 문의 상태는 그대로다.
            없거나 삭제된 답변은 404(INQUIRY_REPLY_NOT_FOUND).""")
    RsData<InquiryResponse> editReply(Long inquiryId, Long replyId, @Valid InquiryReplyRequest request);

    @Operation(summary = "이의 제기 판단 (관리자)", description = """
            강의 내용 다름 이의 제기를 인정(`accepted: true`)하거나 기각한다. 판단 안내 답변(`content`)을 함께 달아 답변 완료(ANSWERED)가 된다.
            인정하면 해당 주문 항목의 HP를 구매자에게 반환하고, 기각하면 지급 예정 시각에 판매자에게 지급한다. 판단은 한 번만 한다.
            이의 제기 문의가 아니면 400(NOT_OBJECTION_INQUIRY), 이미 판단했으면 409(OBJECTION_ALREADY_RESOLVED),
            강의 쪽에서 판단할 수 없으면 409(OBJECTION_NOT_FILED, OBJECTION_INQUIRY_MISMATCH). 삭제된 문의는 404(INQUIRY_NOT_FOUND).""")
    RsData<InquiryResponse> resolveObjection(Long userId, Long inquiryId, @Valid ObjectionResolutionRequest request);

    @Operation(summary = "답변 삭제 (관리자)", description = """
            답변을 지운다(204, Soft Delete). 남은 답변이 없으면 문의가 답변 대기(WAITING)로 돌아간다(판단한 이의 제기는 답변 완료로 남는다).
            없거나 삭제된 답변은 404(INQUIRY_REPLY_NOT_FOUND).""")
    ResponseEntity<Void> deleteReply(Long inquiryId, Long replyId);
}
