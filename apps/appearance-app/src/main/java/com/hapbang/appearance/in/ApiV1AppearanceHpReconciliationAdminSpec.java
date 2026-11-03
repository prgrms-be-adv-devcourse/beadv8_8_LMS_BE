package com.hapbang.appearance.in;

import com.hapbang.appearance.app.dto.HpReconciliationRunResDto;
import com.hapbang.appearance.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 서비스 간 HP 대사 관리(관리자) API Swagger 명세 (#581). 매핑은 {@link ApiV1AppearanceHpReconciliationAdminController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 */
@Tag(name = "출연 HP 대사 (관리자)", description = """
        출연 요청서·모집글·출연 상태와 지갑의 보류·지급 기록이 맞는지 비교한 결과를 본다. 매일 04:00(KST)에 자동으로 돌고, 관리자가 바로 실행할 수도 있다.
        **고치지 않고 찾아서 남기기만 한다.** 정정은 원인을 확인한 뒤 HP 작업 다시 시도(`/admin/hp-tasks`)나 보류 무효화로 한다.
        - 진행 중인 건(보류 결과 대기, 재시도를 기다리는 HP 작업, 최근 2시간 안의 변경)은 비교하지 않는다
        - 격리된(DEAD) HP 작업이 있는 건은 비교하지 않고 `HP_TASK_DEAD`로 표시만 한다
        - 지갑 조회가 실패하면(지갑 장애·회로 열림) 그 실행은 `INCOMPLETE`로 끝나고 다음 실행이 같은 범위를 다시 본다
        관리자(`ADMIN`)만 쓸 수 있다 (그 밖의 로그인 회원은 403, 비로그인 401).
        로그인 뒤 정지되거나 관리자 권한을 잃었으면 403 `APPEARANCE_USER_NOT_ACTIVE` / `NOT_ADMIN`이다.""")
public interface ApiV1AppearanceHpReconciliationAdminSpec {

    @Operation(summary = "HP 대사 지금 실행", description = """
            대사를 실행하고 끝날 때까지 기다려 결과(실행 1회와 어긋난 건)를 돌려준다.
            - 지갑 조회가 실패해도 오류 응답이 아니라 200이고 `status`가 `INCOMPLETE`, 이유는 `failureReason`이다
            - 이미 실행 중이면(매일 배치 포함) 409 `HP_RECONCILIATION_RUNNING`""")
    RsData<HpReconciliationRunResDto> run(Long adminId);

    @Operation(summary = "마지막 HP 대사 결과", description = """
            마지막으로 시작한 실행과 그 어긋난 건. 실행 중이면 `RUNNING`이고 어긋난 건은 끝난 뒤에 남는다.
            - `rule`: 어긋난 이유. `HP_TASK_MISMATCH`(상태와 HP 작업이 다름), `HOLD_MISSING`·`HOLD_MISMATCH`·`HOLD_BALANCE_MISMATCH`(보류),
              `HOLD_NOT_CLOSED`(보류 실패인데 보류가 남음), `VOIDED_WHILE_ACTIVE`(진행 중인데 무효화됨),
              `SETTLEMENT_MISSING`·`SETTLEMENT_MISMATCH`·`SETTLEMENT_UNEXPECTED`(지급 건), `WALLET_ONLY_HOLD`(지갑에만 있는 보류),
              `HP_TASK_DEAD`(격리 작업 표시)
            - 실행 기록이 없으면 404 `HP_RECONCILIATION_NOT_FOUND`""")
    RsData<HpReconciliationRunResDto> getLatest(Long adminId);
}
