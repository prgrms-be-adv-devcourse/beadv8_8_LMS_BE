package com.hapbang.coupon.domain;

import java.util.Optional;

/**
 * 발급 요청 상태 판정. DB 최종 결과를 먼저 보고, 없으면 Redis 접수 기록을 본다.
 * <ul>
 *     <li>DB에 결과가 있으면: 요청한 회원의 기록이면 ISSUED/REJECTED, 아니면 NOT_FOUND</li>
 *     <li>DB에 없고 Redis에 접수 기록이 있으면: 요청한 회원의 기록이면 PENDING, 아니면 NOT_FOUND</li>
 *     <li>DB에 없고 Redis에도 없으면 NOT_FOUND, Redis에 닿지 못했으면 UNKNOWN</li>
 * </ul>
 * 요청 ID만 안다고 다른 회원의 결과를 볼 수 없다. 발급 요청 ID는 쿠폰 안에서만 유일해 DB 결과와 Redis 접수 기록을 모두 쿠폰 ID와 함께
 * 찾으므로 쿠폰은 이미 맞다.
 */
public final class IssueStatusRule {

    private IssueStatusRule() {
    }

    public static IssueStatus judge(Optional<DbRecord> dbRecord, RedisLookup redisLookup, long requesterId) {
        if (dbRecord.isPresent()) {
            DbRecord record = dbRecord.get();
            if (record.memberId() != requesterId) {
                return IssueStatus.NOT_FOUND;
            }
            return record.result() == CouponIssueResult.ISSUED ? IssueStatus.ISSUED : IssueStatus.REJECTED;
        }
        return switch (redisLookup) {
            case RedisLookup.Found found -> found.memberId() == requesterId ? IssueStatus.PENDING : IssueStatus.NOT_FOUND;
            case RedisLookup.Absent ignored -> IssueStatus.NOT_FOUND;
            case RedisLookup.Unavailable ignored -> IssueStatus.UNKNOWN;
        };
    }

    /** DB 최종 결과({@code coupon_issue_request})의 판정에 필요한 값. 쿠폰 ID로 찾은 결과다 */
    public record DbRecord(long memberId, CouponIssueResult result) {
    }

    /** Redis 접수 기록 조회 결과 */
    public sealed interface RedisLookup {

        /** 접수 기록이 있다 */
        record Found(long memberId) implements RedisLookup {
        }

        /** 접수 기록이 없다 */
        record Absent() implements RedisLookup {
        }

        /** Redis에 닿지 못해 확인하지 못했다 */
        record Unavailable() implements RedisLookup {
        }

        static RedisLookup found(long memberId) {
            return new Found(memberId);
        }

        static RedisLookup absent() {
            return new Absent();
        }

        static RedisLookup unavailable() {
            return new Unavailable();
        }
    }
}
