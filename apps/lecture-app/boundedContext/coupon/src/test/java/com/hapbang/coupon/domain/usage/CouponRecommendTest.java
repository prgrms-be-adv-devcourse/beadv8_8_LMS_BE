package com.hapbang.coupon.domain.usage;

import static com.hapbang.coupon.domain.usage.UsageFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.MemberCouponStatus;

// 매핑을 생략했을 때의 추천(할인 합 최대, 동률 규칙, 탐색 상한)을 본다
class CouponRecommendTest {

    private static final long LECTURE_A = 1L;
    private static final long LECTURE_B = 2L;

    private final CouponCalculator calculator = new CouponCalculator();

    private CalculationResult recommend(List<UsageItem> items, List<UsableCoupon> owned) {
        return calculator.calculate(items, owned, null, MEMBER_ID, NOW, MAX_NODES);
    }

    private static List<Long> couponIds(ItemResult item) {
        return item.coupons().stream().map(AppliedCoupon::memberCouponId).toList();
    }

    @Test
    void 모든_강의_쿠폰은_할인이_가장_큰_항목에_둔다() {
        // 앞 항목부터 고르면 A에 두어 1,000이지만 B에 두면 3,000이다
        CalculationResult result = recommend(List.of(item(LECTURE_A, 10_000), item(LECTURE_B, 30_000)),
                List.of(platformAny(1).build()));

        assertThat(result.recommended()).isTrue();
        assertThat(result.optimal()).isTrue();
        assertThat(couponIds(result.items().get(0))).isEmpty();
        assertThat(couponIds(result.items().get(1))).containsExactly(1L);
        assertThat(result.totalDiscount()).isEqualTo(3_000);
        assertThat(result.totalBuyerAmount()).isEqualTo(37_000);
    }

    @Test
    void 모든_강의_쿠폰_여러_장의_배치를_비교해_합이_최대인_배치를_고른다() {
        // 정액 5,000은 A(6,000)에, 50%(최대 10,000)는 B(20,000)에 두면 15,000. 반대로 두면 3,000 + 5,000이다
        List<UsableCoupon> owned = List.of(platformAny(1).amount(5_000).build(),
                platformAny(2).rate(50).maxDiscount(10_000L).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 6_000), item(LECTURE_B, 20_000)), owned);

        assertThat(couponIds(result.items().get(0))).containsExactly(1L);
        assertThat(couponIds(result.items().get(1))).containsExactly(2L);
        assertThat(result.totalDiscount()).isEqualTo(15_000);
    }

    @Test
    void 할인_합이_같으면_쿠폰_수가_적은_배치를_고른다() {
        // 판매자 1,000 + 공용 1,000 = 2,000(2장)과 단독 사용 강의 쿠폰 2,000(1장)
        List<UsableCoupon> owned = List.of(seller(1, LECTURE_A).amount(1_000).build(),
                platformAny(2).amount(1_000).build(),
                platformLecture(3, LECTURE_A).amount(2_000).stackable(false).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 10_000)), owned);

        assertThat(couponIds(result.items().getFirst())).containsExactly(3L);
        assertThat(result.totalDiscount()).isEqualTo(2_000);
    }

    @Test
    void 할인_합과_쿠폰_수가_같으면_빨리_만료되는_쿠폰을_고른다() {
        List<UsableCoupon> owned = List.of(platformAny(1).amount(1_000).build(),
                platformAny(2).amount(1_000).expiresAt(EXPIRES_AT.minusSeconds(1)).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 10_000)), owned);

        assertThat(couponIds(result.items().getFirst())).containsExactly(2L);
    }

    @Test
    void 만료도_같으면_발급_쿠폰_ID가_작은_쿠폰을_고른다() {
        List<UsableCoupon> owned = List.of(platformAny(5).amount(1_000).build(), platformAny(3).amount(1_000).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 10_000)), owned);

        assertThat(couponIds(result.items().getFirst())).containsExactly(3L);
    }

    @Test
    void 할인_0인_쿠폰을_더해도_합이_같으면_쿠폰_수가_적은_쪽을_고른다() {
        // 판매자 정액이 1,000을 모두 깎아 플랫폼 정률을 더해도 할인 0이 더해질 뿐이다. 가격 0 항목도 쿠폰을 두지 않는다
        List<UsableCoupon> owned = List.of(seller(1, LECTURE_A).amount(1_000).build(), platformAny(2).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 1_000), item(LECTURE_B, 0)), owned);

        assertThat(couponIds(result.items().get(0))).containsExactly(1L);
        assertThat(couponIds(result.items().get(1))).isEmpty();
        assertThat(result.totalDiscount()).isEqualTo(1_000);
    }

    @Test
    void 쓸_수_없는_쿠폰은_추천_후보에서_뺀다() {
        List<UsableCoupon> owned = List.of(platformAny(1).owner(OTHER_MEMBER_ID).build(),
                platformAny(2).expiresAt(NOW).build(),
                platformAny(3).status(MemberCouponStatus.RESERVED).build());

        CalculationResult result = recommend(List.of(item(LECTURE_A, 10_000)), owned);

        assertThat(result.totalDiscount()).isZero();
        assertThat(result.optimal()).isTrue();
        assertThat(result.items().getFirst().coupons()).isEmpty();
    }

    @Test
    void 탐색_상한을_넘으면_찾은_최선을_최적이_아니라고_표시한다() {
        List<UsageItem> items = List.of(item(LECTURE_A, 10_000), item(LECTURE_B, 30_000));
        List<UsableCoupon> owned = List.of(platformAny(1).build());

        CalculationResult cut = calculator.calculate(items, owned, null, MEMBER_ID, NOW, 1);

        assertThat(cut.recommended()).isTrue();
        assertThat(cut.optimal()).isFalse();
        // 처음 해(앞 항목부터 가장 큰 조합)는 A에 둔 1,000이다
        assertThat(cut.totalDiscount()).isEqualTo(1_000);
        assertThat(recommend(items, owned).totalDiscount()).isEqualTo(3_000);
    }

    @Test
    void 추천은_가능한_모든_배치_중_할인_합_최대와_그중_최소_쿠폰_수를_고른다() {
        // 발급 쿠폰마다 둘 항목(또는 안 씀)을 모두 나열해 적용 규칙(apply)을 통과한 배치 중 최선과 비교한다
        Random random = new Random(784);
        for (int round = 0; round < 300; round++) {
            List<UsageItem> items = randomItems(random);
            List<UsableCoupon> owned = randomCoupons(random, items.size());

            CalculationResult result = recommend(items, owned);
            long[] best = bruteForce(items, owned);

            assertThat(result.optimal()).isTrue();
            assertThat(result.totalDiscount()).as("round %d", round).isEqualTo(best[0]);
            assertThat(result.items().stream().mapToLong(item -> item.coupons().size()).sum())
                    .as("round %d", round).isEqualTo(best[1]);
        }
    }

    private static List<UsageItem> randomItems(Random random) {
        int count = 1 + random.nextInt(3);
        List<UsageItem> items = new ArrayList<>();
        for (int lecture = 1; lecture <= count; lecture++) {
            items.add(item(lecture, random.nextInt(4) == 0 ? 0 : 500L * random.nextInt(61)));
        }
        return items;
    }

    private static List<UsableCoupon> randomCoupons(Random random, int itemCount) {
        int count = random.nextInt(6);
        List<UsableCoupon> coupons = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            // 주문에 없는 강의(itemCount + 1)를 대상으로 한 쿠폰도 섞는다
            long lecture = 1 + random.nextInt(itemCount + 1);
            UsageFixture.CouponBuilder builder = switch (random.nextInt(3)) {
                case 0 -> seller(id, lecture);
                case 1 -> platformLecture(id, lecture).basePrice(1_000L * (1 + random.nextInt(20)));
                default -> platformAny(id).maxDiscount(random.nextBoolean() ? null : 500L * (1 + random.nextInt(10)));
            };
            if (random.nextBoolean()) {
                builder.rate(1 + random.nextInt(100));
            } else {
                builder.amount(500L * (1 + random.nextInt(20)));
            }
            builder.stackable(random.nextInt(4) != 0)
                    .expiresAt(EXPIRES_AT.plusSeconds(random.nextInt(3)));
            coupons.add(builder.build());
        }
        return coupons;
    }

    /** [최대 할인 합, 그 할인 합을 내는 배치의 최소 쿠폰 수] */
    private long[] bruteForce(List<UsageItem> items, List<UsableCoupon> owned) {
        int n = items.size();
        int[] placement = new int[owned.size()];
        long bestDiscount = -1;
        long bestCount = Long.MAX_VALUE;
        int combinations = (int) Math.pow(n + 1, owned.size());
        for (int code = 0; code < combinations; code++) {
            int rest = code;
            for (int i = 0; i < placement.length; i++) {
                placement[i] = rest % (n + 1) - 1;
                rest /= n + 1;
            }
            long discount = 0;
            long count = 0;
            boolean valid = true;
            for (int itemIndex = 0; itemIndex < n && valid; itemIndex++) {
                List<UsableCoupon> placed = new ArrayList<>();
                for (int i = 0; i < placement.length; i++) {
                    if (placement[i] == itemIndex) {
                        placed.add(owned.get(i));
                    }
                }
                try {
                    discount += calculator.apply(items.get(itemIndex), placed, MEMBER_ID, NOW).discount();
                    count += placed.size();
                } catch (CouponUsageViolationException exception) {
                    valid = false;
                }
            }
            if (valid && (discount > bestDiscount || discount == bestDiscount && count < bestCount)) {
                bestDiscount = discount;
                bestCount = count;
            }
        }
        return new long[] {bestDiscount, bestCount};
    }
}
