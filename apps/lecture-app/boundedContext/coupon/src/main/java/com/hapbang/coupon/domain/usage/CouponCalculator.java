package com.hapbang.coupon.domain.usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;

/**
 * 쿠폰 금액 규칙·중첩 규칙·추천. Spring에 의존하지 않고 상태가 없다.
 *
 * <h2>금액 규칙</h2>
 * <ul>
 *     <li>항목 원가격 P에 판매자 쿠폰 → 플랫폼 쿠폰 순서로 적용한다. 각 쿠폰은 적용 직전 남은 금액에 걸린다.</li>
 *     <li>정률 d = floor(남은 금액 × 할인율 / 100), 정액 d = min(정액, 남은 금액). 정액이 가격보다 크면 결제액은 0 HP다.</li>
 *     <li>모든 강의 대상 정률은 장당 최대 할인액을 넘지 않는다. 특정 강의 대상 플랫폼 쿠폰은 장당 예산 M(정률
 *     floor(강의 가격 × 할인율 / 100), 정액 min(정액, 강의 가격))을 넘지 않는다.</li>
 *     <li>판매자 쿠폰은 f = 0·s = d, 플랫폼 쿠폰은 s = 0·f = d. 구매자 결제액 = P − D, 판매자 지급액 = P − S.</li>
 * </ul>
 *
 * <h2>중첩 규칙</h2>
 * 한 항목에 판매자 쿠폰 최대 1장 + 플랫폼 쿠폰 최대 1장. 단독 사용 쿠폰은 그 항목에 혼자만 둔다. 한 발급 쿠폰은 한 항목에만 둔다.
 * 쿠폰 운영 상태(중단·종료)로는 거르지 않는다.
 *
 * <h2>추천</h2>
 * 주문 전체 할인 합 D가 최대인 배치를 분기 한정(branch and bound)으로 찾는다. 항목마다 가능한 조합(없음, 판매자 1, 플랫폼 1,
 * 판매자 1 + 플랫폼 1)을 만들고, 모든 강의 대상 쿠폰처럼 여러 항목에 둘 수 있는 쿠폰은 한 항목에만 쓰이게 하며 항목을 차례로
 * 정한다. 남은 항목의 최대 할인 합을 더해도 지금 최선에 못 미치면 그 가지를 버린다. 동률은 적은 쿠폰 수 → 빠른 만료 →
 * 작은 발급 쿠폰 ID로 가린다(그래도 같으면 요청 항목 순서대로 각 항목의 발급 쿠폰 ID를 비교해 결과를 하나로 정한다).
 * 할인 0인 쿠폰을 따로 빼지 않는다.
 * <p>
 * 최악 복잡도는 항목 수 n, 항목당 조합 수 k에 대해 O(k^n)이므로 시도한 조합 수를 {@code maxSearchNodes}로 자른다. 자르면
 * 그때까지 찾은 최선(처음에 항목마다 가장 큰 조합을 고른 탐욕해로 시작한다)을 {@code optimal=false}로 돌려준다.
 */
public final class CouponCalculator {

    /**
     * 주문 전체를 계산한다.
     *
     * @param items          주문 항목. 같은 강의가 두 번 있으면 안 된다(호출자가 먼저 400으로 거절한다)
     * @param owned          회원의 발급 쿠폰. 추천이면 후보 전체, 선택 매핑이면 매핑한 쿠폰을 담는다(매핑한 쿠폰이 여기 없으면
     *                       사용 불가로 거절한다)
     * @param selections     {@code null}이면 추천, 빈 목록이면 미사용(할인 0), 값이 있으면 그 매핑만 검증·계산한다(매핑에 없는
     *                       강의는 채우지 않는다)
     * @param maxSearchNodes 추천에서 시도할 조합 수 상한
     * @throws CouponUsageViolationException 선택 매핑이 사용 규칙을 어겼다
     */
    public CalculationResult calculate(List<UsageItem> items, List<UsableCoupon> owned, List<Selection> selections,
                                       long memberId, Instant now, int maxSearchNodes) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(owned, "owned");
        Objects.requireNonNull(now, "now");
        requireDistinctLectures(items);
        if (selections == null) {
            return recommend(items, owned, memberId, now, maxSearchNodes);
        }
        Map<Long, List<UsableCoupon>> byLecture = mapSelections(items, owned, selections);
        List<ItemResult> results = items.stream()
                .map(item -> apply(item, byLecture.getOrDefault(item.lectureId(), List.of()), memberId, now))
                .toList();
        return result(false, false, results);
    }

    /**
     * 한 항목에 쿠폰들을 규칙대로 적용한다(사용 가능 → 적용 대상 → 중첩 순서로 검사, 판매자 → 플랫폼 순서로 적용).
     * 예약의 재검증·재계산도 이것을 쓴다.
     *
     * @param couponsForItem 이 항목에 둘 쿠폰. 빈 목록이면 할인 0
     * @throws CouponUsageViolationException 규칙 위반. 위반한 발급 쿠폰 ID를 담는다(사용 가능·적용 대상은 발급 쿠폰 ID
     *                                       오름차순으로 먼저 걸린 쿠폰, 단독 사용 위반은 단독 표시 쿠폰)
     */
    public ItemResult apply(UsageItem item, List<UsableCoupon> couponsForItem, long memberId, Instant now) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(now, "now");
        List<UsableCoupon> coupons = couponsForItem.stream()
                .sorted(Comparator.comparingLong(UsableCoupon::memberCouponId))
                .toList();
        for (UsableCoupon coupon : coupons) {
            if (!coupon.isUsableBy(memberId, now)) {
                throw violation(CouponDomainError.COUPON_NOT_USABLE, coupon);
            }
            if (!coupon.appliesTo(item.lectureId())) {
                throw violation(CouponDomainError.COUPON_NOT_APPLICABLE, coupon);
            }
        }
        UsableCoupon seller = null;
        UsableCoupon platform = null;
        for (UsableCoupon coupon : coupons) {
            boolean taken = coupon.type() == CouponType.SELLER ? seller != null : platform != null;
            if (taken) {
                // 같은 발급 쿠폰을 두 번 둔 것도 여기서 걸린다
                throw violation(CouponDomainError.COUPON_STACKING_VIOLATION, coupon);
            }
            if (coupon.type() == CouponType.SELLER) {
                seller = coupon;
            } else {
                platform = coupon;
            }
        }
        if (seller != null && platform != null && !(seller.stackable() && platform.stackable())) {
            throw violation(CouponDomainError.COUPON_STACKING_VIOLATION, seller.stackable() ? platform : seller);
        }
        return compute(item, seller, platform);
    }

    // 선택 매핑 -----------------------------------------------------------------------------------------------

    private static Map<Long, List<UsableCoupon>> mapSelections(List<UsageItem> items, List<UsableCoupon> owned,
                                                               List<Selection> selections) {
        Map<Long, UsableCoupon> ownedById = new HashMap<>();
        owned.forEach(coupon -> ownedById.put(coupon.memberCouponId(), coupon));
        Set<Long> lectures = new HashSet<>();
        items.forEach(item -> lectures.add(item.lectureId()));

        Set<Long> placed = new HashSet<>();
        Map<Long, List<UsableCoupon>> byLecture = new LinkedHashMap<>();
        for (Selection selection : selections) {
            long memberCouponId = selection.memberCouponId();
            if (!placed.add(memberCouponId)) {
                throw new CouponUsageViolationException(CouponDomainError.COUPON_STACKING_VIOLATION, memberCouponId);
            }
            UsableCoupon coupon = ownedById.get(memberCouponId);
            if (coupon == null) {
                throw new CouponUsageViolationException(CouponDomainError.COUPON_NOT_USABLE, memberCouponId);
            }
            if (!lectures.contains(selection.lectureId())) {
                throw new CouponUsageViolationException(CouponDomainError.COUPON_NOT_APPLICABLE, memberCouponId);
            }
            byLecture.computeIfAbsent(selection.lectureId(), key -> new ArrayList<>()).add(coupon);
        }
        return byLecture;
    }

    // 추천 ---------------------------------------------------------------------------------------------------

    private static CalculationResult recommend(List<UsageItem> items, List<UsableCoupon> owned, long memberId,
                                               Instant now, int maxSearchNodes) {
        Map<Long, UsableCoupon> usable = new LinkedHashMap<>();
        owned.stream()
                .filter(coupon -> coupon.isUsableBy(memberId, now))
                .forEach(coupon -> usable.putIfAbsent(coupon.memberCouponId(), coupon));
        List<List<Option>> options = items.stream()
                .map(item -> optionsFor(item, usable.values()))
                .toList();
        Search search = new Search(options, maxSearchNodes);
        search.run();
        List<ItemResult> results = search.best.stream().map(Option::result).toList();
        return result(true, !search.aborted, results);
    }

    /** 한 항목에 둘 수 있는 조합 전부. 좋은 순서(할인 큰 순 → 동률 규칙)로 정렬한다 */
    private static List<Option> optionsFor(UsageItem item, Iterable<UsableCoupon> usable) {
        List<UsableCoupon> sellers = new ArrayList<>();
        List<UsableCoupon> platforms = new ArrayList<>();
        for (UsableCoupon coupon : usable) {
            if (coupon.appliesTo(item.lectureId())) {
                (coupon.type() == CouponType.SELLER ? sellers : platforms).add(coupon);
            }
        }
        List<Option> options = new ArrayList<>();
        options.add(Option.of(compute(item, null, null), List.of()));
        sellers.forEach(seller -> options.add(Option.of(compute(item, seller, null), List.of(seller))));
        platforms.forEach(platform -> options.add(Option.of(compute(item, null, platform), List.of(platform))));
        for (UsableCoupon seller : sellers) {
            for (UsableCoupon platform : platforms) {
                if (seller.stackable() && platform.stackable()) {
                    options.add(Option.of(compute(item, seller, platform), List.of(seller, platform)));
                }
            }
        }
        options.sort(Option.ORDER);
        return List.copyOf(options);
    }

    /** 항목 하나에 고른 조합. 동률 비교용으로 쿠폰들의 만료 시각·발급 쿠폰 ID를 오름차순으로 들고 있다 */
    private record Option(ItemResult result, List<Long> memberCouponIds, List<Instant> expiries) {

        /** 할인 큰 순 → 적은 쿠폰 수 → 빠른 만료 → 작은 발급 쿠폰 ID */
        static final Comparator<Option> ORDER = Comparator
                .comparingLong((Option option) -> -option.discount())
                .thenComparingInt(Option::count)
                .thenComparing(Option::expiries, CouponCalculator::compareLists)
                .thenComparing(Option::memberCouponIds, CouponCalculator::compareLists);

        static Option of(ItemResult result, List<UsableCoupon> coupons) {
            return new Option(result,
                    coupons.stream().map(UsableCoupon::memberCouponId).sorted().toList(),
                    coupons.stream().map(UsableCoupon::expiresAt).sorted().toList());
        }

        long discount() {
            return result.discount();
        }

        int count() {
            return memberCouponIds.size();
        }
    }

    /** 분기 한정 탐색. 한 번 쓰고 버린다 */
    private static final class Search {

        private final List<List<Option>> options;
        private final int maxNodes;
        /** remainingBound[i] = i번째 항목부터 끝까지 항목별 최대 할인의 합(쿠폰 충돌을 무시한 상한) */
        private final long[] remainingBound;
        private final Option[] current;
        private final Set<Long> used = new HashSet<>();

        private List<Option> best;
        private long bestDiscount;
        private int bestCount;
        private long nodes;
        private boolean aborted;

        Search(List<List<Option>> options, int maxNodes) {
            this.options = options;
            this.maxNodes = maxNodes;
            int n = options.size();
            this.remainingBound = new long[n + 1];
            for (int i = n - 1; i >= 0; i--) {
                remainingBound[i] = remainingBound[i + 1] + options.get(i).getFirst().discount();
            }
            this.current = new Option[n];
        }

        void run() {
            consider(greedy());
            dfs(0, 0, 0);
        }

        /** 항목 순서대로, 앞 항목이 쓰지 않은 쿠폰 중 가장 좋은 조합을 고른다. 탐색이 잘려도 돌려줄 해다 */
        private List<Option> greedy() {
            Set<Long> taken = new HashSet<>();
            List<Option> chosen = new ArrayList<>();
            for (List<Option> itemOptions : options) {
                Option pick = itemOptions.stream()
                        .filter(option -> option.memberCouponIds().stream().noneMatch(taken::contains))
                        .findFirst()
                        .orElseThrow();
                taken.addAll(pick.memberCouponIds());
                chosen.add(pick);
            }
            return chosen;
        }

        private void dfs(int index, long discount, int count) {
            if (index == current.length) {
                consider(List.of(current));
                return;
            }
            for (Option option : options.get(index)) {
                if (aborted) {
                    return;
                }
                long bound = discount + option.discount() + remainingBound[index + 1];
                if (bound < bestDiscount) {
                    // 조합은 할인 큰 순이라 뒤의 조합도 못 미친다
                    return;
                }
                int nextCount = count + option.count();
                if (bound == bestDiscount && nextCount > bestCount) {
                    // 할인이 같아져도 쿠폰 수가 이미 많아 이길 수 없다
                    continue;
                }
                if (option.memberCouponIds().stream().anyMatch(used::contains)) {
                    continue;
                }
                if (++nodes > maxNodes) {
                    aborted = true;
                    return;
                }
                used.addAll(option.memberCouponIds());
                current[index] = option;
                dfs(index + 1, discount + option.discount(), nextCount);
                option.memberCouponIds().forEach(used::remove);
            }
        }

        private void consider(List<Option> candidate) {
            if (best == null || compareAssignments(candidate, best) < 0) {
                best = List.copyOf(candidate);
                bestDiscount = best.stream().mapToLong(Option::discount).sum();
                bestCount = best.stream().mapToInt(Option::count).sum();
            }
        }
    }

    /** 배치 비교: 할인 합 큰 순 → 쿠폰 수 적은 순 → 빠른 만료 → 작은 발급 쿠폰 ID → 항목 순서대로 각 항목의 발급 쿠폰 ID */
    private static int compareAssignments(List<Option> left, List<Option> right) {
        int byDiscount = Long.compare(sumDiscount(right), sumDiscount(left));
        if (byDiscount != 0) {
            return byDiscount;
        }
        int byCount = Integer.compare(sumCount(left), sumCount(right));
        if (byCount != 0) {
            return byCount;
        }
        int byExpiry = compareLists(sortedFlat(left, Option::expiries), sortedFlat(right, Option::expiries));
        if (byExpiry != 0) {
            return byExpiry;
        }
        int byId = compareLists(sortedFlat(left, Option::memberCouponIds), sortedFlat(right, Option::memberCouponIds));
        if (byId != 0) {
            return byId;
        }
        for (int i = 0; i < left.size(); i++) {
            int byPlacement = compareLists(left.get(i).memberCouponIds(), right.get(i).memberCouponIds());
            if (byPlacement != 0) {
                return byPlacement;
            }
        }
        return 0;
    }

    private static long sumDiscount(List<Option> options) {
        return options.stream().mapToLong(Option::discount).sum();
    }

    private static int sumCount(List<Option> options) {
        return options.stream().mapToInt(Option::count).sum();
    }

    private static <T extends Comparable<? super T>> List<T> sortedFlat(
            List<Option> options, Function<Option, List<T>> values) {
        return options.stream().flatMap(option -> values.apply(option).stream()).sorted().toList();
    }

    /** 오름차순 목록의 사전식 비교. 앞부분이 같으면 짧은 쪽이 앞이다 */
    private static <T extends Comparable<? super T>> int compareLists(List<T> left, List<T> right) {
        int size = Math.min(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int compared = left.get(i).compareTo(right.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    // 금액 ---------------------------------------------------------------------------------------------------

    /** 판매자 쿠폰 → 플랫폼 쿠폰 순서로 적용한다. 검사는 끝난 뒤다 */
    private static ItemResult compute(UsageItem item, UsableCoupon seller, UsableCoupon platform) {
        long remaining = item.price();
        long platformDiscount = 0;
        long sellerDiscount = 0;
        List<AppliedCoupon> applied = new ArrayList<>(2);
        for (UsableCoupon coupon : new UsableCoupon[] {seller, platform}) {
            if (coupon == null) {
                continue;
            }
            long base = remaining;
            long discount = discountOf(coupon, base);
            long platformAmount = coupon.type() == CouponType.PLATFORM ? discount : 0;
            long sellerAmount = coupon.type() == CouponType.SELLER ? discount : 0;
            applied.add(new AppliedCoupon(coupon.memberCouponId(), coupon.couponId(), coupon.type(),
                    applied.size() + 1, base, discount, platformAmount, sellerAmount, coupon.generation(),
                    coupon.policyVersion()));
            remaining -= discount;
            platformDiscount += platformAmount;
            sellerDiscount += sellerAmount;
        }
        long discount = item.price() - remaining;
        return new ItemResult(item, discount, platformDiscount, sellerDiscount, remaining,
                item.price() - sellerDiscount, applied);
    }

    /** 남은 금액 base에 이 쿠폰을 적용한 할인 d(0 이상 base 이하) */
    private static long discountOf(UsableCoupon coupon, long base) {
        long discount = coupon.discountType() == DiscountType.RATE
                ? rateOf(base, coupon.discountValue())
                : Math.min(coupon.discountValue(), base);
        if (coupon.targetType() == CouponTargetType.ANY_LECTURE && coupon.discountType() == DiscountType.RATE
                && coupon.maxDiscountAmount() != null) {
            discount = Math.min(discount, coupon.maxDiscountAmount());
        }
        if (coupon.type() == CouponType.PLATFORM && coupon.targetType() == CouponTargetType.LECTURE) {
            // 장당 예산 M. 가격 인상 제한이 지켜지면 결과가 바뀌지 않는 방어다
            long budgetPerCoupon = coupon.discountType() == DiscountType.RATE
                    ? rateOf(coupon.basePrice(), coupon.discountValue())
                    : Math.min(coupon.discountValue(), coupon.basePrice());
            discount = Math.min(discount, budgetPerCoupon);
        }
        return discount;
    }

    /** floor(amount × rate / 100). amount × rate가 long을 넘지 않게 100으로 나눈 몫과 나머지로 나눠 계산한다 */
    static long rateOf(long amount, long rate) {
        return Math.addExact(Math.multiplyExact(amount / 100, rate), amount % 100 * rate / 100);
    }

    // 공통 ---------------------------------------------------------------------------------------------------

    private static CalculationResult result(boolean recommended, boolean optimal, List<ItemResult> items) {
        long totalDiscount = items.stream().mapToLong(ItemResult::discount).sum();
        long totalBuyerAmount = items.stream().mapToLong(ItemResult::buyerAmount).sum();
        return new CalculationResult(recommended, optimal, items, totalDiscount, totalBuyerAmount);
    }

    private static void requireDistinctLectures(List<UsageItem> items) {
        Set<Long> lectures = new HashSet<>();
        for (UsageItem item : items) {
            if (!lectures.add(item.lectureId())) {
                throw new IllegalArgumentException("한 주문에 같은 강의가 두 번 있습니다: " + item.lectureId());
            }
        }
    }

    private static CouponUsageViolationException violation(CouponDomainError error, UsableCoupon coupon) {
        return new CouponUsageViolationException(error, coupon.memberCouponId());
    }
}
