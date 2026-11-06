package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 선착순 발급 접수 저장소(Redis). 접수 판정의 권위 데이터({@code issue-control}·stock·users·requests)와 발급 Stream을 다룬다.
 * 접수는 완료가 아니다. 확정은 Stream 뒤의 작업자·소비자가 DB에 한다.
 * <p>
 * {@code issue-control}의 status는 fence(쿠폰 DB {@code @Version} 값)로 순서를 정한다. 열기는 저장된 fence보다 클 때만,
 * 막기는 같거나 클 때만 반영하므로 늦게 도착한 옛 쓰기가 더 새 DB 상태를 덮지 못한다.
 * <p>
 * 저장소에 닿지 못하면 {@link CouponIssueStoreUnavailableException}을 던진다. 성공으로 간주하지 않는다.
 */
public interface CouponIssueStore {

    /**
     * 발급을 준비한다: stock·epoch·발급 기간·fence를 쓰고 {@code issue-control}을 ready로 만든다. 접수는 열지 않는다
     * (새로 만들거나 epoch가 바뀌면 status는 접수 불가인 READY이고, 같은 epoch로 이미 있으면 status를 바꾸지 않는다). 같은 값으로 다시 불러도 안전하다:
     * 같은 epoch면 남은 stock을 덮지 않는다. fence는 {@code issue-control}이 없을 때만 {@link IssueControl#fence()}로 쓰고,
     * 있으면 그대로 둔다.
     *
     * @throws IllegalStateException 같은 epoch가 이미 종료(CLOSED)됐다
     */
    void prepare(IssueControl control);

    /**
     * 접수를 연다({@code issue-control} status를 OPEN으로). 쿠폰을 DB에 OPEN으로 커밋한 뒤 그 커밋의 version을 fence로 넘긴다.
     * {@code fence}가 저장된 fence보다 클 때만 OPEN과 fence를 쓴다. 같거나 작으면 더 새 쓰기(막기·열기)가 이미 있으므로 바꾸지 않고
     * {@link AllowResult#SUPERSEDED}다(예외가 아니다. 해석은 호출자가 한다).
     *
     * @throws IllegalStateException                  이미 종료(CLOSED)됐다
     * @throws CouponIssueStoreUnavailableException 준비 기록이 없거나(준비 전·만료) 저장소에 닿지 못했다
     */
    AllowResult allowIssue(long couponId, long fence);

    /**
     * 신규 접수를 막는다({@code issue-control} status를 바꾸고 다시 읽어 확인한다). {@code fence}가 저장된 fence보다 같거나 클 때만
     * 쓰고 fence를 그 값으로 바꾼다. 작으면 더 새 쓰기가 이미 있으므로 무시하고 성공이다. CLOSED는 SUSPENDED로 되돌리지 않는다.
     * 준비 전({@code issue-control} 없음)이면 이미 막힌 것으로 보고 아무것도 쓰지 않는다.
     * 확인(status가 막힌 값이거나 저장된 fence가 {@code fence}보다 큼)하지 못하면 {@link CouponIssueStoreUnavailableException}이다.
     *
     * @param status SUSPENDED 또는 CLOSED
     * @param fence  쿠폰 DB version. 막는 쪽은 DB 전이 전 지금 version으로 한 번, 전이 커밋 뒤 그 version으로 한 번 부른다
     */
    void block(long couponId, String status, long fence);

    /**
     * 접수를 시도한다(issue.lua). 반환값은 Lua 그대로다: 1 접수, 2 같은 요청 재응답, 0 매진, -1 이미 발급, -2 준비 안 됨,
     * -3 기간 아님, -9 오류. 해석은 도메인이 한다.
     */
    long tryIssue(IssueAttempt attempt);

    /** 요청 ID의 접수 기록. 없으면 빈 값 */
    Optional<IssueReceipt> findRequest(long couponId, String issueRequestId);

    /** {@link #allowIssue} 결과 */
    enum AllowResult {
        /** OPEN으로 바꿨다 */
        APPLIED,
        /** 같거나 더 새 fence의 쓰기가 이미 있어 바꾸지 않았다 */
        SUPERSEDED
    }

    /**
     * @param stock         발급할 수량(아직 DB에 확정되지 않은 남은 수량)
     * @param keysExpireAt  쿠폰별 key의 만료 시각(발급 종료 + 여유). Stream에는 만료를 두지 않는다
     * @param fence         준비할 때 읽은 쿠폰 DB version. {@code issue-control}을 새로 만들 때만 쓴다
     */
    record IssueControl(long couponId, long epoch, long stock, Instant issueStartAt, Instant issueEndAt,
            Instant keysExpireAt, long fence) {
    }

    /**
     * @param issuedAt  최초 접수 시각(앱 시계). 재전송으로 바뀌지 않는다(같은 요청 ID면 Lua가 2를 돌려주고 기록을 바꾸지 않는다)
     * @param expiresAt 발급 쿠폰의 만료 시각(도메인이 issuedAt으로 계산)
     */
    record IssueAttempt(long couponId, long memberId, String issueRequestId, long epoch, Instant issuedAt,
            Instant expiresAt) {
    }

    /** 접수 기록: 접수한 회원과 최초 접수 시각 */
    record IssueReceipt(long memberId, Instant acceptedAt) {
    }
}
