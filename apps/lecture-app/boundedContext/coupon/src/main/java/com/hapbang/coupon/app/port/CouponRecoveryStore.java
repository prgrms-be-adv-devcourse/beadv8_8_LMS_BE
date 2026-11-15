package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Map;

import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.RecoveryRebuildPlan;
import com.hapbang.coupon.domain.RecoveryRedisState;

/**
 * Redis 유실 복구가 쓰는 발급 저장소(Redis) 작업. 접수 경로({@link CouponIssueStore})와 같은 쿠폰별 key를 다룬다.
 * <p>
 * 신규 접수 차단은 issue-control의 {@code ready=0}과 {@code hold=RECOVERY}로 한다. issue.lua는 둘 중 하나만 있어도 -2로 거절하고,
 * 접수 열기(allow-issue.lua)는 ready≠1이면 열지 않으므로 복구가 끝나기 전에는 중단·재개·다시 열기로도 접수가 열리지 않는다.
 * 차단할 때 issue-control의 {@code recoveryId}에 복구 ID를 쓰고, 재구축은 그 값이 자기 복구 ID이고 {@code ready=0}일 때만 한다(재구축 토큰).
 * 프로세스 변경 감지는 issue-control의 {@code runId}(준비·재구축 때 쓴 Redis {@code INFO server}의 run_id)를 지금 값과 비교한다.
 * <p>
 * 저장소에 닿지 못하거나 결과를 읽지 못하면 {@link CouponIssueStoreUnavailableException}을 던진다.
 */
public interface CouponRecoveryStore {

    /**
     * 복구 1단계: 신규 접수를 막고 issue-control의 recoveryId에 {@code recoveryId}를 쓴다. issue-control이 없으면(유실) 아무것도 쓰지
     * 않는다. 저장된 recoveryId가 더 크면(더 새 복구가 있다 — 늦게 도착한 이전 복구의 차단) 접수는 막되 recoveryId는 바꾸지 않고
     * {@link BlockResult#stale()}로 알려 준다.
     */
    BlockResult block(long couponId, long recoveryId);

    /** 복구 판단에 필요한 Redis 상태를 읽는다. requests는 HSCAN으로 끝까지 읽는다 */
    Snapshot snapshot(long couponId);

    /**
     * issue-control이 있는지, 그 runId가 지금 Redis 프로세스(INFO server run_id)와 같은지 읽는다(쓰기 없음). 재개 결과를 모르는 복구의
     * 취소 판단에 쓴다.
     */
    RecoveryRedisState redisState(long couponId);

    /** issue-control의 접수 차단 상태와 key 만료 시각. 상태 조회의 유실 판정에 쓴다 */
    IssueControlState issueControlState(long couponId);

    /**
     * 복구 4단계: 쿠폰의 Redis 발급 데이터(issue-control·stock·users·requests·sold_out)를 지우고 {@code plan}으로 한 번에 다시 만든다.
     * 접수는 막힌 채다(ready=0, hold=RECOVERY). issue-control이 있으면 그 recoveryId가 {@code recoveryId}이고 ready=0일 때만 한다.
     *
     * @return 다시 만들었는지, 쓰지 않았다면 왜인지. 결과를 알 수 없으면(Redis에 닿지 못했다) 예외다
     */
    RebuildResult rebuild(long couponId, long recoveryId, RecoveryRebuildPlan plan, RebuildControl control);

    /**
     * 복구 5단계: 재구축한 새 epoch를 활성화한다(ready=1, hold=RECOVERY 해제, status는 fence 규칙으로). issue-control의 recoveryId가
     * {@code recoveryId}일 때만 연다. 이미 같은 epoch·recoveryId로 열려 있으면 다시 써서 연 결과를 돌려준다(멱등).
     *
     * @return 열었는지, 열지 않았다면(아무것도 쓰지 않았다) 왜인지. 결과를 알 수 없으면(Redis에 닿지 못했다) 예외다
     */
    ActivateResult activate(long couponId, long recoveryId, long newEpoch, String status, long fence);

    /** 프로세스 변경 점검: runId가 지금 run_id와 다르면 접수를 막는다(자동으로 다시 열지 않는다) */
    ProcessCheck checkProcess(long couponId);

    /**
     * @param controlPresent  issue-control이 있었다(없으면 아무것도 쓰지 않았다)
     * @param processChanged  저장된 runId가 없거나 지금 Redis 프로세스와 다르다(재시작·유실 의심)
     * @param acceptsWereOpen 이번 차단 전에 ready=1이었다(방금까지 접수·재발행 배치 전송이 시작될 수 있었다)
     * @param stale           issue-control의 recoveryId가 이 복구보다 새 복구라 recoveryId를 바꾸지 않았다(접수는 막았다). 이 복구는 이미
     *                        끝났다
     */
    record BlockResult(boolean controlPresent, boolean processChanged, boolean acceptsWereOpen, boolean stale) {

        /** 낡지 않은 차단 결과 */
        public BlockResult(boolean controlPresent, boolean processChanged, boolean acceptsWereOpen) {
            this(controlPresent, processChanged, acceptsWereOpen, false);
        }
    }

    /**
     * @param present      issue-control이 있다
     * @param ready        ready=1이다(접수 준비가 끝났고 복구 차단이 없다)
     * @param hold         hold 사유. 없으면 null
     * @param keysExpireAt 쿠폰별 key의 만료 시각(Redis 기록 보관 기간 끝). 없거나 읽을 수 없으면 null
     */
    record IssueControlState(boolean present, boolean ready, String hold, Instant keysExpireAt) {

        /** keysExpireAt을 보지 않는 곳에서 쓴다 */
        public IssueControlState(boolean present, boolean ready, String hold) {
            this(present, ready, hold, null);
        }

        public static IssueControlState missing() {
            return new IssueControlState(false, false, null, null);
        }

        /** 복구 차단(hold=RECOVERY)이 걸려 있다 */
        public boolean recoveryHold() {
            return "RECOVERY".equals(hold);
        }
    }

    /**
     * @param controlPresent issue-control이 있다
     * @param processChanged issue-control의 runId가 없거나 지금 프로세스와 다르다. issue-control이 없으면 false
     * @param stock          가용 재고. 없으면 null
     * @param requests       요청 ID → requests 원문 값
     */
    record Snapshot(boolean controlPresent, boolean processChanged, Long stock, Map<String, String> requests) {

        public Snapshot {
            requests = Map.copyOf(requests);
        }
    }

    /**
     * 재구축하는 issue-control의 준비 값({@link CouponIssueStore.IssueControl}과 같은 뜻).
     *
     * @param status DB 쿠폰 상태(OPEN·SUSPENDED·CLOSED). 접수 여부는 재개 때 다시 쓴다
     * @param fence  읽은 쿠폰 DB version. 저장된 fence가 더 크면 그 값을 둔다
     * @param now    지금 시각. key 만료 시각이 이보다 앞이면 재구축하지 않는다
     */
    record RebuildControl(Instant issueStartAt, Instant issueEndAt, Instant keysExpireAt, long fence,
                          ExpiryType expiryType, Integer expiryDays, Instant fixedExpiresAt, String status, Instant now) {
    }

    /** {@link #rebuild} 결과 */
    enum RebuildResult {
        /** 다시 만들었다 */
        APPLIED,
        /** 재구축 토큰이 맞지 않아(다른 복구의 차단이거나 이미 재개됨) 쓰지 않았다 */
        FENCED,
        /** 인수·key 타입 오류이거나 run_id를 읽지 못해 쓰지 않았다 */
        REJECTED
    }

    /** {@link #activate} 결과. {@link #opened()}가 아니면 아무것도 쓰지 않았다 */
    enum ActivateResult {
        /** 접수 차단을 풀고 status를 썼다 */
        APPLIED,
        /** 접수 차단은 풀었고, 더 새 fence의 쓰기가 있어 status는 그대로 두었다 */
        STATUS_SUPERSEDED,
        /** issue-control이 없다 */
        MISSING,
        /** issue-control의 recoveryId가 이 복구가 아니다(다른 복구가 막았다) */
        FENCED,
        /** issue-control의 epoch가 재구축한 새 epoch가 아니다 */
        EPOCH_MISMATCH,
        /** 재구축 뒤 Redis 프로세스가 바뀌었다(그 사이 기록이 사라졌을 수 있다) */
        PROCESS_CHANGED,
        /** 인수·key 타입 오류이거나 run_id를 읽지 못했다 */
        REJECTED;

        /** 접수 차단을 풀었다 */
        public boolean opened() {
            return this == APPLIED || this == STATUS_SUPERSEDED;
        }
    }

    /** {@link #checkProcess} 결과 */
    enum ProcessCheck {
        /** 같은 프로세스 */
        SAME,
        /** runId가 없어(이 기능 전에 준비한 key) 지금 값을 기록했다 */
        ADOPTED,
        /** 다른 프로세스라 이번에 접수를 막았다 */
        BLOCKED,
        /** 다른 프로세스이고 이미 복구로 막혀 있다 */
        ALREADY_BLOCKED,
        /** issue-control이 없다 */
        MISSING
    }
}
