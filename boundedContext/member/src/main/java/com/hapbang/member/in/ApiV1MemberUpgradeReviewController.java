package com.hapbang.member.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;
import com.hapbang.member.app.MemberUpgradeReviewFacade;
import com.hapbang.member.domain.UpgradeRequestStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 MemberUpgradeReviewFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/members/admin/upgrade-requests")
@RequiredArgsConstructor
public class ApiV1MemberUpgradeReviewController implements ApiV1MemberUpgradeReviewSpec {

    private final MemberUpgradeReviewFacade memberUpgradeReviewFacade;

    /** 심사 목록 (기본 심사 대기, 오래된 신청부터, 승인된 채널 포함) */
    @Override
    @GetMapping
    public RsData<Page<UpgradeReviewResponse>> requests(
            @RequestParam(name = "status", defaultValue = "PENDING") UpgradeRequestStatus status,
            Pageable pageable) {
        return RsData.ok("승급 심사 목록을 조회했습니다.",
                memberUpgradeReviewFacade.findRequests(status, pageable).map(UpgradeReviewResponse::from));
    }

    /** 승인 (인증된 인플루언서로, 그 회원 세션도 커밋 뒤 갱신) */
    @Override
    @PostMapping("/{requestId}/approve")
    public ResponseEntity<Void> approve(@LoginUserId Long adminId, @PathVariable("requestId") Long requestId) {
        memberUpgradeReviewFacade.approve(adminId, requestId);
        return ResponseEntity.noContent().build();
    }

    /** 반려 (사유 필수, 500자 이하) */
    @Override
    @PostMapping("/{requestId}/reject")
    public ResponseEntity<Void> reject(@LoginUserId Long adminId, @PathVariable("requestId") Long requestId,
                                       @Valid @RequestBody RejectRequest request) {
        memberUpgradeReviewFacade.reject(adminId, requestId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
