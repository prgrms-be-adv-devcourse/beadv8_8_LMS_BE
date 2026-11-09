package com.hapbang.coupon.app.port;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hapbang.coupon.domain.IssueHoldReason;
import com.hapbang.coupon.domain.IssueReconciliationRule.MissingUser;
import com.hapbang.coupon.domain.IssueReconciliationRule.SoldOutRepair;

/**
 * 대사·적체 차단·기록 정리용 발급 저장소(Redis) 접근. 접수 판정 데이터(issue-control·stock·users·requests·sold_out)를 읽고,
 * 신규 접수 보류(hold)와 정리만 쓴다. 재고(stock)를 임의로 늘리거나 줄이지 않는다.
 * <p>
 * 저장소에 닿지 못하거나 key 타입이 잘못되었으면 {@link CouponIssueStoreUnavailableException}을 던진다.
 */
public interface CouponReconciliationStore {

    /** 한 쿠폰의 Redis 기록을 한 번에(같은 시점으로) 읽는다 */
    Snapshot snapshot(long couponId);

    /**
     * 신규 접수 보류를 쓴다. 보류가 없거나 이 사유보다 가벼운 사유일 때만 쓴다({@link IssueHoldReason#canOverride}).
     * issue-control이 없으면 쓰지 않는다(이미 접수가 막혀 있다).
     *
     * @return 이번에 썼는가
     */
    boolean hold(long couponId, IssueHoldReason reason);

    /**
     * 신규 접수 보류를 푼다. 보류가 이 사유일 때만 푼다(다른 사유의 보류는 그대로 둔다).
     *
     * @return 이번에 풀었는가
     */
    boolean releaseHold(long couponId, IssueHoldReason reason);

    /**
     * 지금 신규 접수 보류 사유를 읽는다(쓰기 없음).
     *
     * @return 보류 사유. issue-control이 없거나 보류가 없으면 null
     */
    IssueHoldReason readHold(long couponId);

    /**
     * DB 최종 결과를 확인한 요청 기록을 requests에서 지운다. 호출자가 정리 대상(ISSUED, REJECTED이고 보상 표식 있음, 이전 epoch이고
     * DB 결과 있음)만 넘긴다.
     *
     * @return 지운 수
     */
    long removeRequests(long couponId, List<String> issueRequestIds);

    /**
     * 매진 표시와 회원 기록을 고친다. 매진 표시는 그때의 재고로 다시 확인하고, 회원은 그 요청이 아직 보상되지 않았을 때만 넣는다.
     * 재고·요청 기록은 바꾸지 않는다.
     */
    RepairResult repair(long couponId, SoldOutRepair soldOut, List<MissingUser> missingUsers);

    /**
     * 쿠폰의 Redis key를 모두 지운다. 요청 기록이 하나도 없고 접수가 열려 있지 않을 때만 지운다.
     *
     * @return 지웠는가(이미 없었던 것 포함)
     */
    boolean cleanUp(long couponId);

    /**
     * 한 시점의 Redis 기록.
     *
     * @param controlExists issue-control이 있는가. 없으면 준비 전이거나 유실·정리됐다
     * @param ready         issue-control의 ready가 1인가(복구 1단계는 0으로 막는다)
     * @param status        issue-control의 status. 없으면 null
     * @param epoch         issue-control의 epoch. 없거나 읽을 수 없으면 null
     * @param hold          신규 접수 보류 사유. 없으면 null
     * @param stock         가용 재고. key가 없거나 정수가 아니면 null
     * @param soldOut       매진 표시가 있는가
     * @param users         접수한 회원 ID
     * @param requests      요청 ID → 접수 기록 원문
     */
    record Snapshot(boolean controlExists, boolean ready, String status, Long epoch, IssueHoldReason hold, Long stock,
                    boolean soldOut, Set<Long> users, Map<String, String> requests) {

        public Snapshot {
            users = Set.copyOf(users);
            requests = Map.copyOf(requests);
        }

        /** 이 쿠폰의 key(issue-control·재고·매진 표시·회원·요청 기록)가 하나라도 있는가 */
        public boolean redisKeysExist() {
            return controlExists || stock != null || soldOut || !users.isEmpty() || !requests.isEmpty();
        }
    }

    /**
     * @param soldOutChange 매진 표시 변경: 1 씀, -1 지움, 0 그대로
     * @param addedUsers    users에 넣은 회원 수
     */
    record RepairResult(int soldOutChange, long addedUsers) {
    }
}
