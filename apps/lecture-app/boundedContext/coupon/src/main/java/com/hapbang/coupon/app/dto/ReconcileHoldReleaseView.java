package com.hapbang.coupon.app.dto;

import com.hapbang.coupon.domain.IssueHoldReason;

/**
 * 대사 불일치 보류(RECONCILE) 해제 결과.
 *
 * @param released 이번에 보류를 풀었는가. 이미 보류가 없었으면 false(다시 보낸 요청도 성공으로 응답한다)
 * @param hold     해제 뒤 신규 접수 보류 사유. 없으면 null
 */
public record ReconcileHoldReleaseView(long couponId, boolean released, IssueHoldReason hold) {
}
