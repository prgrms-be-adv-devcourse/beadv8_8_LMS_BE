package com.hapbang.appearance.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceWithdrawalInternalFacade;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴 확인 내부 API (#624). Swagger 명세는 {@link ApiV1AppearanceWithdrawalInternalSpec}, 계약은 docs/api/internal-withdrawals.md 3장
 * (출연 쪽 설계는 docs/appearance/module.md 26장). 로직은 {@link AppearanceWithdrawalInternalFacade}만 부른다.
 */
@RestController
@RequestMapping("/api/v1/internal/appearances/users")
@RequiredArgsConstructor
public class ApiV1AppearanceWithdrawalInternalController implements ApiV1AppearanceWithdrawalInternalSpec {

    private final AppearanceWithdrawalInternalFacade appearanceWithdrawalInternalFacade;

    @Override
    @GetMapping("/{userId}/withdrawal-blocks")
    public RsData<WithdrawalBlocksDto> getWithdrawalBlocks(@PathVariable("userId") Long userId) {
        return RsData.ok("탈퇴 확인을 마쳤습니다.", appearanceWithdrawalInternalFacade.findBlocks(userId));
    }
}
