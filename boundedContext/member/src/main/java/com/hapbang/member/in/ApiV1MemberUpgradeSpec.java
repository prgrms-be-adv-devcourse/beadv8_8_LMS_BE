package com.hapbang.member.in;

import java.util.List;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 승급 API Swagger 명세. 매핑은 {@link ApiV1MemberUpgradeController}. 성공 응답은 RsData의 data에 담는다 */
@Tag(name = "승급", description = "인플루언서 → 인증된 인플루언서(강의 등록·판매) 승급 신청")
public interface ApiV1MemberUpgradeSpec {

    @Operation(summary = "승급 신청",
            description = "인플루언서만 (유저·인증된 인플루언서·관리자는 403). 사유 필수·500자 이하 (어기면 400). "
                    + "심사 대기(PENDING)로 저장되고 관리자가 승인하면 인증된 인플루언서가 된다 (세션 권한도 바로 바뀜). "
                    + "이미 심사 대기 중인 신청이 있으면 409 UPGRADE_REQUEST_ALREADY_PENDING. 반려된 뒤에는 다시 신청할 수 있다. "
                    + "정상 회원이 아니면 404 MEMBER_NOT_FOUND.")
    RsData<UpgradeRequestResponse> request(Long userId, UpgradeApplyRequest request);

    @Operation(summary = "내 승급 신청 내역",
            description = "로그인한 회원. 최신 신청부터. 상태: PENDING(심사 대기) / APPROVED(승인) / REJECTED(반려, 사유 포함, 다시 신청 가능).")
    RsData<List<UpgradeRequestResponse>> myRequests(Long userId);
}
