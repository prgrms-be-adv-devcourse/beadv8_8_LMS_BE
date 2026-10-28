package com.hapbang.member.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberFacade;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/members/upgrade-requests")
@RequiredArgsConstructor
public class ApiV1MemberUpgradeController implements ApiV1MemberUpgradeSpec {

    private final MemberFacade memberFacade;

    /** 승급 신청 (인플루언서만) */
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<UpgradeRequestResponse> request(@LoginUserId Long userId,
                                                  @Valid @RequestBody UpgradeApplyRequest request) {
        return RsData.created("승급을 신청했습니다.",
                UpgradeRequestResponse.from(memberFacade.requestUpgrade(userId, request.reason())));
    }

    /** 내 승급 신청 내역 (최신순) */
    @Override
    @GetMapping("/me")
    public RsData<List<UpgradeRequestResponse>> myRequests(@LoginUserId Long userId) {
        return RsData.ok("내 승급 신청 내역을 조회했습니다.",
                memberFacade.findMyUpgradeRequests(userId).stream().map(UpgradeRequestResponse::from).toList());
    }
}
