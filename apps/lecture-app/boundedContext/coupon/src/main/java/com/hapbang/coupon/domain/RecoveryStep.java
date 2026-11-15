package com.hapbang.coupon.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Redis 유실 복구 실행의 단계(마친 단계). 절차는 operations.md "복구와 대사" 1~5이고, 전이 규칙은 {@link RecoveryRun}에 있다.
 * <ol>
 *     <li>{@link #BLOCKED}: 복구 실행·새 epoch 후보를 DB에 기록하고 신규 접수를 막았다</li>
 *     <li>{@link #BOUNDARY_FIXED}: 남은 접수 기록 중 DB에 없는 요청을 재발행하고 Kafka·DLT 경계를 기록했다</li>
 *     <li>{@link #RESULTS_CHECKED}: 경계까지의 요청이 DB 결과로 정리됐는지 확인하고 미처리 건수를 기록했다</li>
 *     <li>{@link #REBUILDING}: 재구축을 선점했다(Redis를 다시 만드는 중이거나, 결과를 알 수 없게 실패했다). Redis가 이미 바뀌었을 수 있어
 *     취소·결과 재확인은 하지 않고 재구축만 다시 한다</li>
 *     <li>{@link #REBUILT}: DB 확정 결과로 Redis(재고·회원·요청 결과·issue-control)를 새 epoch로 다시 만들었다(접수는 막힌 채)</li>
 *     <li>{@link #RESUMING}: 재개를 선점했다(Redis 접수 차단을 푸는 중이거나, 결과를 알 수 없게 실패했다). Redis 접수가 이미 열렸을 수 있어
 *     재구축·취소는 하지 않고 재개만 다시 한다</li>
 *     <li>{@link #RESUMED}: 수량 대사가 맞아 새 epoch를 활성화하고 접수 차단을 풀었다. 끝난 복구다</li>
 * </ol>
 * {@link #ABORTED}는 재구축 전에 관리자가 취소한 복구다. 끝난 복구로 보지만 접수 차단은 풀지 않는다(새 복구로 이어 간다).
 */
public enum RecoveryStep {
    BLOCKED,
    BOUNDARY_FIXED,
    RESULTS_CHECKED,
    REBUILDING,
    REBUILT,
    RESUMING,
    RESUMED,
    ABORTED;

    private static final Set<RecoveryStep> FINISHED = EnumSet.of(RESUMED, ABORTED);

    /** 아직 끝나지 않은 복구인가. 끝나지 않은 복구가 있는 쿠폰은 새 복구를 시작하지 않는다 */
    public boolean isActive() {
        return !FINISHED.contains(this);
    }

    /** 끝난 단계(재개·취소). 끝나지 않은 복구를 찾는 조회에 넘긴다 */
    public static Set<RecoveryStep> finished() {
        return EnumSet.copyOf(FINISHED);
    }
}
