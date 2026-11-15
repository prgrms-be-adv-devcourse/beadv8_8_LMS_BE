package com.hapbang.coupon.domain.usage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 예약·해제 작업 기록의 내용 키: {@code m{memberId}|o{orderId}|{정규화한 요청의 SHA-256 16진수}}.
 * 같은 작업 ID로 온 요청이 처음 요청과 같은 내용인지 이 값으로 비교한다.
 * <p>
 * 정규화: 요청을 순서와 무관한 조각(문자열)의 모음으로 보고, 조각을 정렬해 이어 붙인 값을 해시한다. 그래서 항목·쿠폰의 순서가
 * 달라도 같은 키다. 조각 안에 순서와 무관한 하위 목록(항목의 쿠폰들)이 있으면 {@link #part}로 하위 목록까지 정렬해 한 조각을
 * 만든다. 모든 조각은 길이를 앞에 붙여 이어 붙이므로 값 안에 구분 문자가 있어도 다른 요청과 섞이지 않는다.
 * <pre>{@code
 * // 예약: 항목마다 한 조각, 항목의 쿠폰은 하위 목록
 * UsageContentKey.of(memberId, orderId, items.stream()
 *         .map(item -> UsageContentKey.part("i" + item.orderItemId() + "|l" + item.lectureId() + ...,
 *                 item.coupons().stream().map(c -> "c" + c.memberCouponId() + "|g" + c.expectedGeneration() + ...).toList()))
 *         .toList());
 * // 해제: 사용 시도마다 한 조각과 사유 조각
 * UsageContentKey.of(memberId, orderId, List.of("u" + id + "|g" + generation, ..., "r" + reason));
 * }</pre>
 */
public final class UsageContentKey {

    private UsageContentKey() {
    }

    /**
     * @param parts 요청을 이루는 조각. 순서는 결과에 영향이 없다. 같은 조각이 두 번 있으면 두 번 센다
     */
    public static String of(long memberId, long orderId, Collection<String> parts) {
        return "m" + memberId + "|o" + orderId + "|" + sha256Hex(canonical(parts));
    }

    /** 머리 값과 순서와 무관한 하위 목록으로 한 조각을 만든다. 하위 목록은 정렬해 붙인다 */
    public static String part(String head, Collection<String> children) {
        return lengthPrefixed(Objects.requireNonNull(head, "head")) + canonical(children);
    }

    private static String canonical(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        StringBuilder builder = new StringBuilder().append('[');
        values.stream()
                .map(value -> Objects.requireNonNull(value, "조각은 null일 수 없습니다."))
                .sorted()
                .forEach(value -> builder.append(lengthPrefixed(value)));
        return builder.append(']').toString();
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }
}
