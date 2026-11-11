package com.hapbang.auxiliary.notification.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.notification.app.BackfillResult;
import com.hapbang.auxiliary.notification.app.NotificationAdminFacade;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** 알림 관리자 API(`docs/api/notification.md`, ND-079). 운영자 도구이며 알림센터 화면이 부르지 않는다 */
@Tag(name = "Notification Admin", description = "알림 운영(관리자)")
@RestController
@RequestMapping("/api/v1/notifications/admin")
@RequiredArgsConstructor
public class ApiV1NotificationAdminController {

    private final NotificationAdminFacade facade;

    @Operation(summary = "회원 복제본 Backfill 재실행", description = "회원 목록 내부 API를 마지막 페이지까지 읽어 알림 회원 복제본을 "
            + "채운다(버전이 더 클 때만, 파기된 회원은 그대로). 끝날 때까지 기다리고 결과 수를 준다. 다른 실행이 돌고 있으면 409 "
            + "BACKFILL_RUNNING이다.")
    @PostMapping("/user-replica/backfill")
    public RsData<BackfillResult> rerunUserBackfill() {
        return RsData.ok("회원 복제본 Backfill을 마쳤습니다.", facade.rerunUserBackfill());
    }
}
