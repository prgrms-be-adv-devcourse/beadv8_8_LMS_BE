package com.hapbang.coupon.app.port;

/**
 * 쿠폰의 매진 상태. 매진 뒤 몰리는 접수를 발급 Lua 전에 거절하는 데 쓴다. 최종 판정은 발급 Lua다. 구현은 Redis 매진 표시(sold_out)를
 * 짧게 메모리에 담아 두는 {@code out/redis}의 캐시다.
 */
public interface CouponSoldOutStatus {

    /** 매진으로 알고 있는가. 확인하지 못하면 false(매진 아님)다. 그때 판정은 발급 Lua가 한다 */
    boolean isSoldOut(long couponId);

    /** 발급 Lua가 매진(0)을 돌려줬다 */
    void markSoldOut(long couponId);
}
