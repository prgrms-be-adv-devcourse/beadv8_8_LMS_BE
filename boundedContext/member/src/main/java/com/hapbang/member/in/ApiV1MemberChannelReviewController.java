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
import com.hapbang.member.app.MemberChannelReviewFacade;
import com.hapbang.member.domain.ChannelVerificationStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 MemberChannelReviewFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/members/admin/channels")
@RequiredArgsConstructor
public class ApiV1MemberChannelReviewController implements ApiV1MemberChannelReviewSpec {

    private final MemberChannelReviewFacade memberChannelReviewFacade;

    /** 심사 목록 (기본 심사 대기, 오래된 신청부터) */
    @Override
    @GetMapping
    public RsData<Page<ChannelReviewResponse>> channels(
            @RequestParam(name = "status", defaultValue = "PENDING") ChannelVerificationStatus status,
            Pageable pageable) {
        return RsData.ok("채널 심사 목록을 조회했습니다.",
                memberChannelReviewFacade.findChannels(status, pageable).map(ChannelReviewResponse::from));
    }

    /** 승인 (유저면 인플루언서로, 그 회원 세션도 커밋 뒤 갱신) */
    @Override
    @PostMapping("/{channelId}/approve")
    public ResponseEntity<Void> approve(@LoginUserId Long adminId, @PathVariable("channelId") Long channelId) {
        memberChannelReviewFacade.approve(adminId, channelId);
        return ResponseEntity.noContent().build();
    }

    /** 반려 (사유 필수, 500자 이하) */
    @Override
    @PostMapping("/{channelId}/reject")
    public ResponseEntity<Void> reject(@LoginUserId Long adminId, @PathVariable("channelId") Long channelId,
                                       @Valid @RequestBody RejectRequest request) {
        memberChannelReviewFacade.reject(adminId, channelId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
