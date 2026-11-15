package com.hapbang.coupon.out.redis;

/**
 * 쿠폰 Redis key 이름. key는 이 클래스에서만 만든다.
 * <p>
 * key는 모두 쿠폰별 {@code coupon:<couponId>:<종류>}(예: {@code coupon:42:stock})다. Redis Cluster의 hash tag는 두지 않아
 * 지금의 단일 Redis 구성에서만 맞다(Cluster로 바꾸면 한 쿠폰의 key를 한 slot에 모으는 설계를 다시 해야 한다).
 * <ul>
 *     <li>stock(String): 남은 수량</li>
 *     <li>users(Set): 접수한 회원 ID</li>
 *     <li>requests(Hash): 요청 ID → {@code 회원 ID|접수 시각|ACCEPTED|만료 시각|e접수 epoch}(시각은 epoch 밀리초) 뒤에 표식
 *     {@code |PUBLISHED}·{@code |COMPENSATED}. 접수 epoch 필드가 없는 옛 형식도 있다. 형식은 {@code domain.IssueRequestRecord}가
 *     정한다</li>
 *     <li>issue-control(Hash): ready·status(READY·OPEN·SUSPENDED·CLOSED)·epoch·startAt·endAt·keysExpireAt(시각은 epoch 밀리초)·
 *     fence(마지막으로 반영한 쿠폰 DB version)·만료 계산 입력·hold(신규 접수 보류 사유)</li>
 *     <li>sold_out(String): 매진 표시</li>
 * </ul>
 * 쿠폰별 key는 모두 {@code keysExpireAt}(발급 종료 + 여유)에 만료된다. DB 결과가 없는 접수 기록이 남은 쿠폰은 재발행·대사 배치가
 * 만료(와 issue-control의 {@code keysExpireAt})를 지금 + 여유까지 늦춘다(extend-keys.lua).
 */
public final class CouponRedisKeys {

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
