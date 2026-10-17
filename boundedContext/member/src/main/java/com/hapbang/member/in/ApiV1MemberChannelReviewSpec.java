package com.hapbang.member.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.global.response.RsData;
import com.hapbang.member.domain.ChannelVerificationStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 채널 심사 API Swagger 명세. 매핑은 {@link ApiV1MemberChannelReviewController}. 승인·반려는 204 */
@Tag(name = "채널 심사 (관리자)", description = "팔로워 10명 미만으로 심사 대기 중인 채널 승인·반려 (유저 → 인플루언서)")
public interface ApiV1MemberChannelReviewSpec {

    @Operation(summary = "채널 심사 목록",
            description = "관리자만 (그 외 403). status 기본 PENDING(심사 대기), REJECTED·APPROVED로 이력도 본다. "
                    + "오래된 신청부터, page·size로 페이징 (정렬은 바꿀 수 없다). 정상 회원의 채널만. "
                    + "채널 주소로 들어가 한 달 누적 방송 10시간을 확인한다.")
    RsData<Page<ChannelReviewResponse>> channels(ChannelVerificationStatus status, Pageable pageable);

    @Operation(summary = "채널 승인",
            description = "관리자만 (그 외 403). 심사 대기 → 승인(204). 회원이 유저면 인플루언서가 되고 그 회원의 로그인 세션 권한도 바로 바뀐다. "
                    + "없는 채널 404 CHANNEL_NOT_FOUND, 심사 대기가 아니면(이미 처리됨 포함) 409 CHANNEL_REVIEW_NOT_ALLOWED, "
                    + "탈퇴 등 정상 회원이 아니면 409 CHANNEL_OWNER_INACTIVE.")
    @ApiResponse(responseCode = "204", description = "승인됨 (본문 없음)")
    ResponseEntity<Void> approve(Long adminId, Long channelId);

    @Operation(summary = "채널 반려",
            description = "관리자만 (그 외 403). 심사 대기 → 반려(204). 사유 필수·500자 이하 (어기면 400). "
                    + "회원은 내 채널 목록에서 사유를 보고 다시 승인 요청할 수 있다. 오류 코드는 승인과 같다.")
    @ApiResponse(responseCode = "204", description = "반려됨 (본문 없음)")
    ResponseEntity<Void> reject(Long adminId, Long channelId, RejectRequest request);
}
