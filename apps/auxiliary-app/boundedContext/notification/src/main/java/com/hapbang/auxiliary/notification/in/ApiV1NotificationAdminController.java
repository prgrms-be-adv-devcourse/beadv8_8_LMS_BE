package com.hapbang.auxiliary.notification.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.notification.app.BackfillRun;
import com.hapbang.auxiliary.notification.app.NotificationAdminFacade;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** 알림 관리자 API(`docs/api/notification.md`, ND-079·ND-092). 운영자 도구이며 알림센터 화면이 부르지 않는다 */
@Tag(name = "Notification Admin", description = "알림 운영(관리자)")
@RestController
@RequestMapping("/api/v1/notifications/admin/user-replica/backfill")
@RequiredArgsConstructor
public class ApiV1NotificationAdminController {

    private final NotificationAdminFacade facade;

    @Operation(summary = "회원 복제본 Backfill 재실행 시작", description = "비동기로 시작하고 202와 실행 ID를 준다. 결과는 실행 조회로 "
            + "본다. 다른 실행이 돌고 있으면 409 BACKFILL_RUNNING, 실행 상태를 기록할 수 없으면 실행하지 않고 503 "
            + "BACKFILL_STATUS_UNAVAILABLE이다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RsData<BackfillRun> start() {
        return RsData.of(HttpStatus.ACCEPTED, "회원 복제본 Backfill을 시작했습니다.", facade.startUserBackfill());
    }

    @Operation(summary = "회원 복제본 Backfill 실행 조회", description = "상태 RUNNING·COMPLETED(결과)·FAILED(실패 코드)·UNKNOWN"
            + "(RUNNING인데 5분 넘게 갱신이 없음). 보관은 7일이고, 없으면 404 BACKFILL_RUN_NOT_FOUND이다.")
    @GetMapping("/{runId}")
    public RsData<BackfillRun> find(@PathVariable String runId) {
        return RsData.ok("회원 복제본 Backfill 실행입니다.", facade.findUserBackfill(runId));
    }
}
