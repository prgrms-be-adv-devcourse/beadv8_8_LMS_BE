package com.hapbang.lecture.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureWithdrawalInternalFacade;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/** 회원 탈퇴 확인 내부 API (#625). Swagger 명세는 {@link ApiV1LectureWithdrawalInternalSpec}, 계약은 docs/api/internal-withdrawals.md */
@RestController
@RequestMapping("/api/v1/internal/lectures/users")
@RequiredArgsConstructor
public class ApiV1LectureWithdrawalInternalController implements ApiV1LectureWithdrawalInternalSpec {

    private final LectureWithdrawalInternalFacade lectureWithdrawalInternalFacade;

    @Override
    @GetMapping("/{userId}/withdrawal-blocks")
    public RsData<WithdrawalBlocksDto> getWithdrawalBlocks(@PathVariable("userId") Long userId) {
        return RsData.ok("탈퇴 확인을 마쳤습니다.", lectureWithdrawalInternalFacade.getBlocks(userId));
    }
}
