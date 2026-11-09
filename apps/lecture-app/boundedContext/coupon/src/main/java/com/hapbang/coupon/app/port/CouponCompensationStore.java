package com.hapbang.coupon.app.port;

import com.hapbang.coupon.domain.IssueCompensation;

/**
 * 영구 거절한 발급 요청의 Redis 수량 보상(compensate.lua). DB에 거절을 확정한 <b>뒤에</b> 트랜잭션 밖에서 부른다.
 * 요청별 보상 표식·epoch 검증·재고 복구·회원 기록 삭제를 한 번에 원자적으로 한다. 같은 요청을 다시 불러도 표식이 있어 다시 복구하지
 * 않으므로 실패 뒤 재시도해도 안전하다.
 * <p>
 * 저장소에 닿지 못하면 {@link CouponIssueStoreUnavailableException}을 던진다. 성공으로 간주하지 않는다.
 */
public interface CouponCompensationStore {

    /**
     * @param epoch 요청이 접수된 epoch(이벤트 값). issue-control의 현재 epoch와 다르면 표식만 남긴다
     * @return 보상 결과. 해석은 {@link IssueCompensation}
     */
    IssueCompensation compensate(long couponId, String issueRequestId, long memberId, long epoch);
}
