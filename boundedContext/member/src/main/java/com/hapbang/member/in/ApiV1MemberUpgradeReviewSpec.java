package com.hapbang.member.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;
import com.hapbang.member.domain.UpgradeRequestStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 승급 심사 API Swagger 명세. 매핑은 {@link ApiV1MemberUpgradeReviewController}. 승인·반려는 204 */
@Tag(name = "승급 심사 (관리자)", description = "인플루언서의 승급 신청 승인·반려 (인플루언서 → 인증된 인플루언서)")
public interface ApiV1MemberUpgradeReviewSpec {

    @Operation(summary = "승급 심사 목록",
            description = "관리자만 (그 외 403). status 기본 PENDING(심사 대기), REJECTED·APPROVED로 이력도 본다. "
                    + "오래된 신청부터, page·size로 페이징 (정렬은 바꿀 수 없다). 정상 회원의 신청만. "
                    + "channels는 그 회원의 승인된 채널 (주소로 들어가 활동을 확인한다).")
    RsData<Page<UpgradeReviewResponse>> requests(UpgradeRequestStatus status, Pageable pageable);

    @Operation(summary = "승급 승인",
            description = "관리자만 (그 외 403). 심사 대기 → 승인(204). 회원이 인증된 인플루언서가 되고 로그인 세션 권한도 바로 바뀐다. "
                    + "없는 신청 404 UPGRADE_REQUEST_NOT_FOUND, 심사 대기가 아니면(이미 처리됨 포함) 409 UPGRADE_REQUEST_REVIEW_NOT_ALLOWED, "
                    + "탈퇴 등 정상 회원이 아니면 409 UPGRADE_REQUEST_MEMBER_INACTIVE, 회원이 더는 인플루언서가 아니면 409 UPGRADE_MEMBER_NOT_INFLUENCER.")
    @ApiResponse(responseCode = "204", description = "승인됨 (본문 없음)")
    ResponseEntity<Void> approve(Long adminId, Long requestId);

    @Operation(summary = "승급 반려",
            description = "관리자만 (그 외 403). 심사 대기 → 반려(204). 사유 필수·500자 이하 (어기면 400). "
                    + "회원은 내 승급 신청 내역에서 사유를 보고 다시 신청할 수 있다. 404·409는 승인과 같다 (인플루언서 여부는 보지 않는다).")
    @ApiResponse(responseCode = "204", description = "반려됨 (본문 없음)")
    ResponseEntity<Void> reject(Long adminId, Long requestId, RejectRequest request);
}
