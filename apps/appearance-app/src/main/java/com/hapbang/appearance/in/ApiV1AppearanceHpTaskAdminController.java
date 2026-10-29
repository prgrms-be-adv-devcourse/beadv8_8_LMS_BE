package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.HpTaskAdminFacade;
import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.global.auth.LoginUserId;
import com.hapbang.appearance.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * HP 작업 관리(관리자) REST API (#397). Swagger 명세는 {@link ApiV1AppearanceHpTaskAdminSpec}. 로직은 {@link HpTaskAdminFacade}만 부른다.
 * <p>
 * 관리자 API는 모든 모듈이 {@code /api/v1/{module}/admin/**} 아래에 두고, 보안 설정이 관리자만 통과시킨다.
 */
@RestController
@RequestMapping("/api/v1/appearances/admin/hp-tasks")
@RequiredArgsConstructor
public class ApiV1AppearanceHpTaskAdminController implements ApiV1AppearanceHpTaskAdminSpec {

    private final HpTaskAdminFacade hpTaskAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<HpTaskAdminResDto>> getTasks(
            @RequestParam(name = "status", defaultValue = "DEAD") HpTaskStatus status,
            Pageable pageable) {
        Page<HpTaskAdminResDto> tasks = hpTaskAdminFacade.getTasks(status, pageable);
        return RsData.ok("HP 작업 목록을 조회했습니다.", tasks);
    }

    @Override
    @PostMapping("/{taskId:\\d+}/retry")
    public RsData<HpTaskAdminResDto> retry(@LoginUserId Long adminId, @PathVariable("taskId") Long taskId) {
        HpTaskAdminResDto retried = hpTaskAdminFacade.retry(adminId, taskId);
        return RsData.ok("HP 작업을 다시 시도했습니다.", retried);
    }
}
