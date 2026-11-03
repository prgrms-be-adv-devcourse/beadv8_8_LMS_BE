package com.hapbang.lecture.in;

import com.hapbang.lecture.app.dto.LectureHpReconciliationRunResponse;
import com.hapbang.lecture.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 서비스 간 HP 대사 API Swagger 명세 (관리자, D74). 매핑은 {@link ApiV1LectureHpReconciliationAdminController} */
@Tag(name = "강의 HP 대사", description = "관리자의 서비스 간 HP 대사: 주문 항목 상태와 지갑 보류·지급을 비교해 어긋난 건을 찾는다. "
        + "고치지 않는다. 정정은 원인을 확인한 뒤 HP 작업 재시도·보류 무효화로 한다")
public interface ApiV1LectureHpReconciliationAdminSpec {

    @Operation(summary = "HP 대사 수동 실행",
            description = "관리자. 지금 대사를 실행하고 끝날 때까지 기다려 결과(어긋난 건 포함)를 준다. 매일 04:30:25 배치와 같은 일을 한다. "
                    + "처음 실행은 지갑 보류 목록을 처음부터 읽어 오래 걸릴 수 있고, 응답 전에 연결이 끊겨도 서버에서는 끝까지 실행되므로 "
                    + "GET /latest로 확인한다. 지갑 조회가 실패하면(회로 열림 포함) 오류가 아니라 200 + status INCOMPLETE + "
                    + "failureReason이다. 이미 실행 중이면 409 HP_RECONCILIATION_RUNNING.")
    RsData<LectureHpReconciliationRunResponse> run(Long adminUserId);

    @Operation(summary = "마지막 HP 대사 결과",
            description = "관리자. 마지막으로 시작한 실행과 어긋난 건. 실행 중이면 status RUNNING이고 어긋난 건은 끝난 뒤 남는다. "
                    + "실행 기록이 없으면 404 HP_RECONCILIATION_NOT_FOUND.")
    RsData<LectureHpReconciliationRunResponse> getLatest();
}
