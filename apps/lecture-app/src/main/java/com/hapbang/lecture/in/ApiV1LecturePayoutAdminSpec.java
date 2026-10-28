package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.dto.PayoutFailureResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 지급 실패 관리 API Swagger 명세 (관리자, D54). 매핑은 {@link ApiV1LecturePayoutAdminController} */
@Tag(name = "강의 지급 실패", description = "관리자의 강의 지급 실패 확인과 지급 격리 해제")
public interface ApiV1LecturePayoutAdminSpec {

    @Operation(summary = "지급 실패 목록",
            description = "관리자. 지급 배치에서 실패한 기록이 있고 아직 지급되지 않은(CONFIRMED) 주문 항목. 최근 실패순으로 고정한다"
                    + "(sort 무시). payoutQuarantinedAt이 있으면 다시 시도해도 풀리지 않는 실패라 격리된 항목이고, "
                    + "없으면 다음 지급 배치에서 다시 시도한다.")
    RsData<Page<PayoutFailureResponse>> getPayoutFailures(Pageable pageable);

    @Operation(summary = "지급 격리 해제",
            description = "관리자. 격리를 해제해 다음 지급 배치에서 다시 시도하게 한다(204). 바로 지급하지 않는다. "
                    + "원인(billing에 남은 지급 예정 건 등)을 먼저 고친 뒤 해제한다. 실패 기록은 남는다. "
                    + "없는 항목은 404 ORDER_ITEM_NOT_FOUND, 격리되지 않은 항목은 409 PAYOUT_NOT_QUARANTINED.")
    ResponseEntity<Void> releasePayoutQuarantine(Long adminUserId, Long orderItemId);
}
