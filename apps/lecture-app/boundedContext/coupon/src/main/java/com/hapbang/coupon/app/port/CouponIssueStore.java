package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.IssueRequestRecord;

/**
 * 선착순 발급 접수 저장소(Redis). 접수 판정의 권위 데이터({@code issue-control}·stock·users·requests·sold_out)를 다룬다.
 * 접수는 완료가 아니다. 확정은 Kafka 소비자가 DB에 한다. requests의 접수 기록이 접수의 복구 근거이며 재발행 배치가 읽는다.
 * <p>
 * {@code issue-control}의 status는 fence(쿠폰 DB {@code @Version} 값)로 순서를 정한다. 열기는 저장된 fence보다 클 때만,
 * 막기는 같거나 클 때만 반영하므로 늦게 도착한 옛 쓰기가 더 새 DB 상태를 덮지 못한다.
 * <p>
 * 저장소에 닿지 못하면 {@link CouponIssueStoreUnavailableException}을 던진다. 성공으로 간주하지 않는다.
 */
public interface CouponIssueStore {

    /**
     * 발급을 준비한다: stock·epoch·발급 기간·fence·만료 계산 입력을 쓰고 {@code issue-control}을 ready로 만든다. 접수는 열지 않는다
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
     * 접수를 시도한다(issue.lua). DB를 읽지 않는다: epoch와 발급 쿠폰의 만료 시각은 {@link #prepare}가 써 둔 {@code issue-control}
     * 값으로 Lua가 정한다. 코드는 Lua 그대로다: 1 접수, 2 같은 요청 재응답, 0 매진, -1 이미 발급, -2 준비 안 됨(준비 전·쿠폰 없음 포함)
     * 또는 신규 접수 보류({@code issue-control}의 hold), -3 기간 아님, -4 요청 ID가 다른 회원의 접수와 겹침, -9 오류(쓰기 실패면 앞의
     * 쓰기를 되돌린 뒤). 해석은 도메인이 한다.
     */
    IssueOutcome tryIssue(IssueAttempt attempt);

    /** 요청 ID의 접수 기록. 없으면 빈 값 */
    Optional<IssueReceipt> findRequest(long couponId, String issueRequestId);

    /**
     * 접수 기록에 Kafka 전송 성공 표식(PUBLISHED)을 붙인다(mark-published.lua). 기록이 없거나 보상(COMPENSATED)을 마친 요청은 바꾸지 않는다.
     * 표식은 DB 완료가 아니고 재발행 판단에 쓰지 않으므로 실패해도 무해하다.
     */
    PublishMark markPublished(long couponId, String issueRequestId);

    /** 매진 표시(sold_out)가 있는가. 매진 메모리 캐시가 짧게 재사용한다. 최종 판정은 issue.lua다 */
    boolean isSoldOut(long couponId);

    /** 접수 기록(requests)이 남아 있는가. 종료(CLOSED)된 쿠폰의 정리 전 여부를 이것으로 본다 */
    boolean hasRequests(long couponId);

    /**
     * 접수 기록(requests)을 HSCAN으로 끝까지 읽어 {@code pageSize}개씩 넘긴다. 읽는 동안 바뀐 기록은 빠지거나 두 번 나올 수 있다(HSCAN 보장).
     * 값은 해석하지 않고 넘긴다(해석은 {@link IssueRequestRecord#parse}).
     *
     * @param pageSize 한 번에 가져오는 개수(HSCAN COUNT 힌트이자 넘기는 묶음 크기)
     */
    void scanRequests(long couponId, int pageSize, Consumer<List<RequestEntry>> page);

    /** {@code issue-control}의 준비·상태·epoch·보류. 없으면 빈 값 */
    Optional<IssueControlState> findIssueControl(long couponId);

    /** {@link #allowIssue} 결과 */
    enum AllowResult {
        /** OPEN으로 바꿨다 */
        APPLIED,
        /** 같거나 더 새 fence의 쓰기가 이미 있어 바꾸지 않았다 */
        SUPERSEDED
    }

    /**
     * @param stock         발급할 수량(아직 DB에 확정되지 않은 남은 수량)
     * @param keysExpireAt  쿠폰별 key의 만료 시각(발급 종료 + 여유)
     * @param fence         준비할 때 읽은 쿠폰 DB version. {@code issue-control}을 새로 만들 때만 쓴다
     * @param expiryType     발급 쿠폰의 만료 방식(RELATIVE·FIXED). 접수 때 Lua가 만료 시각을 계산하는 입력이다
     * @param expiryDays     접수 기준 만료 일수. RELATIVE만, 아니면 null
     * @param fixedExpiresAt 고정 만료 시각. FIXED만, 아니면 null. 밀리초 아래는 버리고 저장한다
     */
    record IssueControl(long couponId, long epoch, long stock, Instant issueStartAt, Instant issueEndAt,
            Instant keysExpireAt, long fence, ExpiryType expiryType, Integer expiryDays, Instant fixedExpiresAt) {
    }

    /**
     * @param issuedAt 접수 시각(앱 시계, 기간 판정의 현재 시각). 같은 요청 ID 재전송이면 기록에 쓰지 않는다(최초 접수 시각이 그대로다)
     */
    record IssueAttempt(long couponId, long memberId, String issueRequestId, Instant issuedAt) {
    }

    /**
     * 접수 시도 결과.
     *
     * @param code      Lua 반환 코드({@link #tryIssue} 참고)
     * @param expiresAt 발급 쿠폰의 만료 시각. 1(접수)이면 이번 접수의 값, 2(재응답)이면 최초 접수의 값이고, 그 밖에는 null
     * @param epoch     접수 당시 {@code issue-control}의 epoch. 1(접수)일 때만 있고 그 밖에는 0이다. 접수 직후 보내는 발급 요청 이벤트에 싣는다
     */
    record IssueOutcome(long code, Instant expiresAt, long epoch) {
    }

    /** 접수 기록: 접수한 회원과 최초 접수 시각 */
    record IssueReceipt(long memberId, Instant acceptedAt) {
    }

    /** {@link #markPublished} 결과 */
    enum PublishMark {
        /** 표식을 붙였다 */
        MARKED,
        /** 이미 표식이 있었다 */
        ALREADY_MARKED,
        /** 기록이 없거나 보상을 마친 요청이라 바꾸지 않았다 */
        SKIPPED
    }

    /**
     * requests의 한 항목.
     *
     * @param value 해석하지 않은 값({@code 회원 ID|접수 시각|ACCEPTED|만료 시각[|표식]})
     */
    record RequestEntry(String issueRequestId, String value) {
    }

    /**
     * {@code issue-control}의 일부.
     *
     * @param ready  준비됨(ready=1)
     * @param status READY·OPEN·SUSPENDED·CLOSED
     * @param epoch  지금 epoch. 없거나 형식이 틀리면 null
     * @param hold   신규 접수 보류 사유. 없으면 null
     */
    record IssueControlState(boolean ready, String status, Long epoch, String hold) {
    }
}
