package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 재발행 대상 판정. 재발행 배치가 requests의 접수 기록마다 쓴다. 같은 요청 ID·같은 값으로 다시 보내므로 중복 전달은 소비자 멱등이 흡수한다.
 * <ul>
 *     <li>영구 거절 보상을 마친 요청(COMPENSATED)은 결과가 이미 DB에 정해진 것이므로 보내지 않는다.</li>
 *     <li>접수 epoch가 지금 {@code issue-control}의 epoch보다 작은(이전 epoch에 접수된) 미확정 요청은 보내지 않는다. 보내도 소비자가
 *     epoch 불일치로 DLT에 격리하므로 소용이 없고, 대사가 이전 epoch 미확정으로 이미 알린다. 접수 epoch가 없는 옛 형식 기록은
 *     해당하지 않는다.</li>
 *     <li>접수 뒤 대기 시간이 지나지 않은 요청은 기다린다(접수 직후 전송·소비 중인 요청과 겹치지 않게).</li>
 *     <li>DB {@code coupon_issue_request}에 결과가 있으면 보내지 않는다.</li>
 *     <li>DLT에 격리돼 아직 처리되지 않은(PENDING·재발행 선점 중인 REPLAYING) 요청은 보내지 않는다.</li>
 *     <li>Kafka 전송 성공 표식(PUBLISHED)은 보지 않는다: ACK 뒤 소비가 실패·유실될 수 있어 DB에 없으면 보낸다.</li>
 * </ul>
 * 접수된 요청을 지워서 해결하지 않는다(기록 정리는 DB 결과를 확인한 대사가 한다).
 */
public final class RepublishRule {

    private RepublishRule() {
    }

    /**
     * DB·DLT를 확인할 후보인가(보상 완료가 아니고, 이전 epoch 기록이 아니고, 대기 시간이 지났다). 재발행 배치는 후보만 모아 DB를 한 번에
     * 확인한다.
     *
     * @param controlEpoch 지금 {@code issue-control}의 epoch
     */
    public static boolean needsCheck(IssueRequestRecord record, Instant now, Duration republishAfter,
                                     long controlEpoch) {
        return !record.compensated() && !staleEpoch(record, controlEpoch) && waited(record, now, republishAfter);
    }

    /**
     * @param controlEpoch 지금 {@code issue-control}의 epoch
     * @param recordedInDb DB {@code coupon_issue_request}에 이 (쿠폰, 요청 ID)의 결과가 있다
     * @param dltPending   DLT 격리 기록이 아직 처리되지 않았다(PENDING·REPLAYING)
     */
    public static Decision decide(IssueRequestRecord record, Instant now, Duration republishAfter, long controlEpoch,
                                  boolean recordedInDb, boolean dltPending) {
        if (record.compensated()) {
            return Decision.COMPENSATED;
        }
        if (staleEpoch(record, controlEpoch)) {
            return Decision.STALE_EPOCH;
        }
        if (!waited(record, now, republishAfter)) {
            return Decision.WAITING;
        }
        if (recordedInDb) {
            return Decision.RECORDED;
        }
        if (dltPending) {
            return Decision.DLT_PENDING;
        }
        return Decision.REPUBLISH;
    }

    /** 이전 epoch에 접수된 기록인가(접수 epoch &lt; 지금 epoch). 접수 epoch가 없는 옛 형식 기록은 아니다 */
    public static boolean staleEpoch(IssueRequestRecord record, long controlEpoch) {
        return record.epoch() != null && record.epoch() < controlEpoch;
    }

    /**
     * 한 쿠폰의 남은 재발행을 이번 회차에서 멈출까. 연속 실패(FAILED·TIMED_OUT) 수가 상한에 닿으면 멈춘다. broker 장애 때 후보마다 ACK 대기로
     * 회차가 끝나지 않는 것을 막는다. 멈춘 요청은 기록을 그대로 두므로 다음 회차가 다시 본다.
     *
     * @param consecutiveFailures ACK 없이 이어진 실패 수(ACK가 끼면 0부터 다시 센다)
     * @param maxConsecutiveFailures 상한(1 이상)
     */
    public static boolean stopAfterFailures(int consecutiveFailures, int maxConsecutiveFailures) {
        return consecutiveFailures >= maxConsecutiveFailures;
    }

    /** 접수 뒤 대기 시간이 지났는가(경과 ≥ 대기). 시계가 접수 시각보다 앞이면 지나지 않은 것이다 */
    private static boolean waited(IssueRequestRecord record, Instant now, Duration republishAfter) {
        return !now.isBefore(record.acceptedAt().plus(republishAfter));
    }

    /** 판정 결과 */
    public enum Decision {
        /** 다시 보낸다 */
        REPUBLISH,
        /** 대기 시간 전이다 */
        WAITING,
        /** DB에 결과가 있다 */
        RECORDED,
        /** 보상을 마쳤다 */
        COMPENSATED,
        /** 이전 epoch에 접수됐다(소비자가 epoch 불일치로 격리하므로 보내지 않는다) */
        STALE_EPOCH,
        /** DLT에서 관리자 판단을 기다린다 */
        DLT_PENDING;

        /**
         * DB 결과가 없는 미확정 요청인가(다시 보낼 요청과 DLT에서 기다리는 요청). 재발행 배치는 이런 요청을 본 쿠폰의 key 만료를 늦춘다.
         * 대기 시간 전(WAITING)은 DB를 확인하지 않은 것이라 넣지 않는다.
         */
        public boolean unresolved() {
            return this == REPUBLISH || this == DLT_PENDING;
        }
    }
}
