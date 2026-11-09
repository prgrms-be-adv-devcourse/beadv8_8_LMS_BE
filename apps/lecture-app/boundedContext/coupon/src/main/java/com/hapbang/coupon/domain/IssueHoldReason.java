package com.hapbang.coupon.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 신규 접수 보류(issue-control {@code hold})의 사유. 값이 있으면 issue.lua가 신규 접수를 -2로 거절한다(기존 요청 재응답·재발행·소비는 계속된다).
 * <p>
 * 보류는 한 번에 하나의 사유만 가진다. 더 무거운 사유가 가벼운 사유를 덮고(복구 &gt; 대사 불일치 &gt; 적체), 가벼운 사유는 무거운 사유를
 * 덮지 못한다. 보류를 푸는 쪽은 자기 사유일 때만 푼다(다른 사유의 보류를 지우지 않는다).
 */
public enum IssueHoldReason {

    /** 재발행 대기 건수가 임계치를 넘었다. 대기 건수가 임계치 이하로 줄면 대사 배치가 푼다 */
    BACKLOG(1),
    /** 대사에서 음수 재고·DB 총량 초과·원인 불명 차이가 나왔다. 원인을 확인할 때까지 자동으로 풀지 않는다 */
    RECONCILE(2),
    /** Redis 유실 복구가 진행 중이다 */
    RECOVERY(3);

    private final int weight;

    IssueHoldReason(int weight) {
        this.weight = weight;
    }

    /** 지금 보류({@code current}, 없으면 null) 위에 이 사유를 쓸 수 있는가. 같은 사유는 다시 쓰지 않는다 */
    public boolean canOverride(IssueHoldReason current) {
        return current == null || weight > current.weight;
    }

    /** 이 사유보다 가벼운(이 사유가 덮을 수 있는) 사유들 */
    public List<IssueHoldReason> lighter() {
        return Arrays.stream(values()).filter(reason -> reason.weight < weight).toList();
    }

    /** issue-control의 hold 값을 읽는다. 비어 있으면 null, 모르는 값이면 가장 무거운 사유처럼 다룬다(덮거나 풀지 않는다) */
    public static IssueHoldReason parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (IssueHoldReason reason : values()) {
            if (reason.name().equals(value)) {
                return reason;
            }
        }
        return RECOVERY;
    }
}
