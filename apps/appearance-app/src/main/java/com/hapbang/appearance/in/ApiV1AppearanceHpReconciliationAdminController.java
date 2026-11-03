package com.hapbang.appearance.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.HpReconciliationAdminFacade;
import com.hapbang.appearance.app.dto.HpReconciliationRunResDto;
import com.hapbang.appearance.global.auth.LoginUserId;
import com.hapbang.appearance.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사 관리(관리자) REST API (#581). Swagger 명세는 {@link ApiV1AppearanceHpReconciliationAdminSpec}.
 * 로직은 {@link HpReconciliationAdminFacade}만 부른다.
 */
@RestController
@RequestMapping("/api/v1/appearances/admin/hp-reconciliations")
@RequiredArgsConstructor
public class ApiV1AppearanceHpReconciliationAdminController implements ApiV1AppearanceHpReconciliationAdminSpec {

    private final HpReconciliationAdminFacade hpReconciliationAdminFacade;

    @Override
    @PostMapping
    public RsData<HpReconciliationRunResDto> run(@LoginUserId Long adminId) {
        return RsData.ok("HP 대사를 실행했습니다.", hpReconciliationAdminFacade.run(adminId));
    }

    @Override
    @GetMapping("/latest")
    public RsData<HpReconciliationRunResDto> getLatest(@LoginUserId Long adminId) {
        return RsData.ok("마지막 HP 대사 결과를 조회했습니다.", hpReconciliationAdminFacade.getLatest(adminId));
    }
}
