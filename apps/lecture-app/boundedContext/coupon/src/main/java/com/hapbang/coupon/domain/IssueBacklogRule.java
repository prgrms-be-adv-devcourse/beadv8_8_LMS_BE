package com.hapbang.coupon.domain;

/**
 * 적체 차단 판정. 재발행 대기 건수(접수 뒤 재발행 대기 시간이 지났는데 DB에 결과가 없고 보상하지 않은 요청 수)가 임계치를 넘으면 신규 접수를
 * 보류(hold=BACKLOG)해 재발행·정리를 먼저 하게 하고, 해제 기준({@code 임계치 × 해제 비율}) 이하로 줄어야 푼다. 임계치와 해제 기준 사이에서는
 * 지금 상태를 그대로 둔다(임계치 근처에서 보류·해제가 회차마다 바뀌지 않게). 접수된 요청을 지워서 해결하지 않는다.
 * <p>
 * 다른 사유(대사 불일치·복구)의 보류는 이 판정이 덮거나 풀지 않는다.
 */
public final class IssueBacklogRule {

    private IssueBacklogRule() {
    }

    /**
     * @param waiting   재발행 대기 건수
     * @param threshold 보류 임계치(설정). 이 값을 넘으면 보류한다
     * @param releaseAt 해제 기준(설정의 임계치 × 해제 비율). 보류 중 대기 건수가 이 값 이하일 때만 푼다. 임계치 이하여야 한다
     * @param current   지금 issue-control의 보류 사유. 없으면 null
     */
    public static Decision decide(long waiting, long threshold, long releaseAt, IssueHoldReason current) {
        if (releaseAt > threshold) {
            throw new IllegalArgumentException("적체 보류 해제 기준은 임계치 이하여야 한다");
        }
        if (waiting > threshold) {
            return IssueHoldReason.BACKLOG.canOverride(current) ? Decision.HOLD : Decision.KEEP;
        }
        return current == IssueHoldReason.BACKLOG && waiting <= releaseAt ? Decision.RELEASE : Decision.KEEP;
    }

    public enum Decision {
        /** hold=BACKLOG를 쓴다 */
        HOLD,
        /** BACKLOG 보류를 푼다 */
        RELEASE,
        /** 바꾸지 않는다(이미 보류 중이거나 더 무거운 사유가 있거나, 보류할 필요가 없거나, 아직 해제 기준까지 줄지 않았다) */
        KEEP
    }
}
