package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * HP 작업 관리(관리자) API Swagger 명세 (#397). 매핑은 {@link ApiV1AppearanceHpTaskAdminController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 */
@Tag(name = "출연 HP 작업 관리 (관리자)", description = """
        출연이 Billing에 요청하는 HP 이동(반환·위약금 정산·지급) 작업을 관리자가 확인하고, 격리된(DEAD) 작업을 다시 실행한다.
        작업은 실패하면 간격을 늘려 가며 자동으로 다시 시도하고, Billing이 거절하거나 재시도를 다 써도 실패하면 격리된다.
        격리돼도 업무 상태(거절·확인·취소)는 되돌리지 않으므로, 원인을 고친 뒤 같은 작업을 다시 실행하는 것이 복구 방법이다.
        관리자(`ADMIN`)만 쓸 수 있다 (그 밖의 로그인 회원은 403 `FORBIDDEN`, 비로그인 401 `UNAUTHORIZED`).""")
public interface ApiV1AppearanceHpTaskAdminSpec {

    @Operation(summary = "HP 작업 목록", description = """
            상태별 HP 작업 목록. `status`를 생략하면 격리된(`DEAD`) 작업이다.
            마지막으로 바뀐 작업부터(격리 작업이면 격리된 순서), 페이지는 page·size(기본 20, 최대 100).
            작업 종류·대상(요청서·모집글, 출연)·금액·시도 횟수·마지막 오류·격리 시각(`completedAt`)·다시 시도 기록을 담는다.""")
    RsData<Page<HpTaskAdminResDto>> getTasks(
            @Parameter(description = "PENDING 대기·재시도 중 / DONE 완료 / DEAD 격리. 생략하면 DEAD") HpTaskStatus status,
            @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "격리된 HP 작업 다시 시도", description = """
            격리된(`DEAD`) 작업을 대기로 되돌리고 **같은 멱등 키**로 바로 1회 실행한 뒤, 실행 뒤 작업을 돌려준다.
            같은 요청 ID로 다시 보내므로 Billing에서 HP가 두 번 움직이지 않는다.
            - 결과는 `status`로 확인한다: 성공 `DONE` / Billing이 다시 거절 `DEAD` /
              결과를 알 수 없는 실패 `PENDING`(시도 횟수를 0부터 다시 세며 재시도 배치가 이어서 실행, 최대 횟수를 넘기면 다시 `DEAD`).
              다시 실패해도 오류 응답이 아니라 200이다
            - 누가 언제 다시 시도했는지 `manualRetryCount`·`lastManualRetryBy`·`lastManualRetryAt`에 남는다
            - 작업이 없으면 404 `HP_TASK_NOT_FOUND`, 격리된 작업이 아니면 409 `HP_TASK_NOT_DEAD`,
              다른 관리자가 동시에 다시 시도하면 409 `CONCURRENT_UPDATE`""")
    RsData<HpTaskAdminResDto> retry(Long adminId, Long taskId);
}
