package com.hapbang.coupon.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 복구 4단계(재구축)에서 Redis에 다시 쓸 값. DB에 확정된 결과만으로 만든다.
 * <ul>
 *     <li>stock = 총수량 − DB 확정 발급 수({@code coupon.issued_count}). 확정 발급 수가 ISSUED 결과 수와 다르면 DB가 어긋난 것이라
 *     만들지 않는다.</li>
 *     <li>users = ISSUED 결과의 회원.</li>
 *     <li>requests = DB에 결과가 있는 요청만. 값 형식은 {@link IssueRequestRecord}이며 만료 시각은 최초 접수 시각으로 다시 계산한다
 *     (Lua와 같은 식). 접수 epoch는 새 epoch가 아니라 DB {@code coupon_issue_request.epoch}(요청이 처리된 epoch)다. ISSUED는 {@code |PUBLISHED}, REJECTED는 {@code |PUBLISHED|COMPENSATED}를 붙인다. REJECTED는 재고·회원 기록에
 *     들지 않으므로 보상 표식을 붙여 영구 거절 보상이 재고를 다시 늘리지 못하게 한다. 같은 요청 ID 재전송은 issue.lua가 기존 접수로
 *     응답하고, 결과는 상태 조회가 DB에서 돌려준다.</li>
 * </ul>
 * 결과가 없는 요청(재발행할 수 없었던 유실 접수)은 넣지 않는다. 그 요청들은 결과를 증명할 수 없다(UNKNOWN).
 *
 * @param requests 요청 ID → requests 값. 순서는 입력 순서다
 */
public record RecoveryRebuildPlan(long newEpoch, long stock, List<Long> memberIds, Map<String, String> requests) {

    public RecoveryRebuildPlan {
        if (stock < 0) {
            throw new IllegalArgumentException("재고는 음수일 수 없다");
        }
        memberIds = List.copyOf(memberIds);
        requests = Map.copyOf(requests);
    }

    /**
     * @throws CouponDomainException DB 확정 발급 수와 ISSUED 결과 수가 다르거나, 한 회원의 ISSUED 결과가 둘 이상이다
     */
    public static RecoveryRebuildPlan of(Coupon coupon, long newEpoch, List<RecoveryIssueResult> results) {
        Objects.requireNonNull(coupon, "coupon");
        Map<String, String> requests = new LinkedHashMap<>();
        LinkedHashSet<Long> members = new LinkedHashSet<>();
        long issued = 0;
        for (RecoveryIssueResult result : results) {
            boolean isIssued = result.result() == CouponIssueResult.ISSUED;
            if (isIssued) {
                issued++;
                if (!members.add(result.memberId())) {
                    throw mismatch("한 회원의 발급 확정 결과가 둘 이상입니다 memberId=" + result.memberId());
                }
            }
            IssueRequestRecord record = new IssueRequestRecord(result.memberId(), result.acceptedAt(),
                    coupon.expiresAtFor(result.acceptedAt()), result.epoch(), true, !isIssued);
            requests.put(result.issueRequestId(), record.format());
        }
        if (issued != coupon.getIssuedCount()) {
            throw mismatch("DB 발급 수(" + coupon.getIssuedCount() + ")와 발급 확정 결과 수(" + issued + ")가 다릅니다.");
        }
        return new RecoveryRebuildPlan(newEpoch, coupon.remainingQuantity(), List.copyOf(members), requests);
    }

    private static CouponDomainException mismatch(String detail) {
        return new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION, "재구축할 수 없습니다. " + detail);
    }
}
