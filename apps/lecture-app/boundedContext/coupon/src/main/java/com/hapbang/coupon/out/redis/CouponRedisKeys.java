package com.hapbang.coupon.out.redis;

/**
 * 쿠폰 Redis key 이름. key는 이 클래스에서만 만든다.
 * <p>
 * 쿠폰별 key는 {@code coupon:<couponId>:<종류>}(예: {@code coupon:42:stock})이고, 발급 Stream은 모든 쿠폰이 함께 쓰는
 * {@link #ISSUE_STREAM} 하나다. 발급 Lua 하나가 쿠폰별 key와 공통 Stream을 같이 쓰므로 Redis Cluster가 아닌 지금의 단일 Redis
 * 구성에서만 맞다(Cluster로 바꾸면 key를 한 slot에 모으는 설계를 다시 해야 한다).
 * <ul>
 *     <li>stock(String): 남은 수량</li>
 *     <li>users(Set): 접수한 회원 ID</li>
 *     <li>requests(Hash): 요청 ID → {@code 회원 ID|접수 시각(epoch 밀리초)|ACCEPTED}</li>
 *     <li>issue-control(Hash): ready·status(READY·OPEN·SUSPENDED·CLOSED)·epoch·startAt·endAt·keysExpireAt(시각은 epoch 밀리초)·
 *     fence(마지막으로 반영한 쿠폰 DB version)</li>
 *     <li>sold_out(String): 매진 표시</li>
 * </ul>
 * 쿠폰별 key는 모두 {@code keysExpireAt}(발급 종료 + 여유)에 만료된다. Stream에는 만료·MAXLEN을 두지 않는다.
 */
public final class CouponRedisKeys {

    /** 모든 쿠폰이 함께 쓰는 발급 Stream */
    public static final String ISSUE_STREAM = "coupon:issue:stream";

    private CouponRedisKeys() {
    }

    public static String stock(long couponId) {
        return perCoupon(couponId, "stock");
    }

    public static String users(long couponId) {
        return perCoupon(couponId, "users");
    }

    public static String requests(long couponId) {
        return perCoupon(couponId, "requests");
    }

    public static String issueControl(long couponId) {
        return perCoupon(couponId, "issue-control");
    }

    public static String soldOut(long couponId) {
        return perCoupon(couponId, "sold_out");
    }

    private static String perCoupon(long couponId, String kind) {
        return "coupon:" + couponId + ":" + kind;
    }
}
