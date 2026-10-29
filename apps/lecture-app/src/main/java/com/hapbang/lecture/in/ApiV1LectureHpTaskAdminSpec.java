package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.lecture.app.dto.LectureHpTaskDetailResponse;
import com.hapbang.lecture.app.dto.LectureHpTaskResponse;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 HP 작업 관리 API Swagger 명세 (관리자, reference.md 8.2.1). 매핑은 {@link ApiV1LectureHpTaskAdminController} */
@Tag(name = "강의 HP 작업", description = "관리자의 강의 HP 작업(보류·반환·지급 요청) 확인과 자동 처리가 멈춘 작업 재시도")
public interface ApiV1LectureHpTaskAdminSpec {

    @Operation(summary = "HP 작업 목록",
            description = "관리자. 최신 작업순으로 고정한다(sort 무시). 필터는 모두 선택이다. "
                    + "강의 작업의 지갑 참조 유형은 항상 LECTURE_ORDER_ITEM이라, 다른 referenceType이면 결과가 없다.")
    RsData<Page<LectureHpTaskResponse>> getHpTasks(
            @Parameter(description = "작업 상태") LectureHpTaskStatus status,
            @Parameter(description = "작업 유형") LectureHpTaskType taskType,
            @Parameter(description = "지갑 참조 유형") BillingReferenceType referenceType,
            @Parameter(description = "주문 ID") Long orderId,
            @Parameter(description = "주문 항목 ID") Long orderItemId,
            @Parameter(description = "마지막 실패 코드") String errorCode,
            Pageable pageable);

    @Operation(summary = "HP 작업 상세",
            description = "관리자. 작업과 시도 이력(원래 오류 코드·진단 정보 포함). 없는 작업은 404 HP_TASK_NOT_FOUND. "
                    + "원인은 billing 데이터에서 먼저 확인한다(예: HOLD_ALREADY_CLOSED면 강의 항목 상태와 billing 보류를 대조).")
    RsData<LectureHpTaskDetailResponse> getHpTask(Long taskId);

    @Operation(summary = "DEAD 작업 재시도",
            description = "관리자. 자동 처리가 멈춘(DEAD) 작업을 다시 대기(PENDING)로 돌린다(204). 바로 실행하지 않고 다음 재시도 배치"
                    + "(기본 1분 안)가 같은 요청 ID로 보내므로 HP가 두 번 움직이지 않는다. 자동 재시도 횟수만 0이 되고 누적 횟수와 "
                    + "마지막 오류는 남는다. 없는 작업은 404 HP_TASK_NOT_FOUND, DEAD가 아니면 409 HP_TASK_NOT_DEAD.")
    ResponseEntity<Void> retry(Long adminUserId, Long taskId);
}
