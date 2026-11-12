package com.hapbang.coupon.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Redis requests(Hash) 값 하나: 발급 요청 ID의 접수 기록. 요청 ID는 Hash의 field라 이 값에는 없다.
 * <p>
 * 형식은 {@code 회원 ID|접수 시각|ACCEPTED|만료 시각|e{접수 epoch}} 뒤에 표식이 붙을 수 있는 문자열이다(시각은 epoch 밀리초).
 * 접수 epoch는 접수할 때 issue-control의 epoch이고, 재발행·재구축이 이벤트에 실을 epoch의 원본이다.
 * <p>
 * epoch 필드가 없는 옛 형식({@code 회원 ID|접수 시각|ACCEPTED|만료 시각}, epoch 필드를 넣기 전에 쓴 기록)도 읽는다. 그때
 * {@link #epoch()}는 {@code null}이고, 쓰는 쪽은 issue-control의 epoch로 대신한다. 옛 기록에 표식을 붙여도 epoch 필드를
 * 새로 만들지 않는다(모르는 값을 지어내지 않는다).
 * <ul>
 *     <li>{@code |PUBLISHED}: 전송이 끝난 기록. 복구 재구축이 DB에 결과가 있는 요청에 붙인다. 접수 직후 전송은 ACK를 기다리지 않아 붙이지
 *     않지만, 그 전에 쓴 기록에는 남아 있을 수 있다. 어떤 판정(재발행·보상·재응답)도 이 표식을 보지 않는다</li>
 *     <li>{@code |COMPENSATED}: 영구 거절의 Redis 보상(재고 복구·회원 기록 제거)을 마쳤다. 같은 요청을 다시 보상하지 않는다</li>
 * </ul>
 * 표식 순서는 정해 두지 않고(읽을 때 어느 순서든 받는다) 같은 표식은 두 번 붙지 않는다. {@link #format()}은 PUBLISHED, COMPENSATED 순서로
 * 쓴다. Lua(issue.lua, compensate.lua, recovery-rebuild.lua)도 같은 형식을 읽고 쓴다.
 *
 * @param memberId    접수한 회원 ID
 * @param acceptedAt  최초 접수 시각(밀리초)
 * @param expiresAt   발급 쿠폰의 만료 시각(밀리초). 접수 때 Lua가 정한 값이다
 * @param epoch       접수 epoch. 옛 형식 기록이면 {@code null}
 * @param published   전송 완료 표식
 * @param compensated 영구 거절 보상 완료 표식
 */
public record IssueRequestRecord(long memberId, Instant acceptedAt, Instant expiresAt, Long epoch,
                                 boolean published, boolean compensated) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String COMPENSATED = "COMPENSATED";

    /** epoch 필드 앞에 붙는 글자. 표식(대문자 단어)과 구별한다 */
    public static final String EPOCH_PREFIX = "e";

    private static final String SEPARATOR = "|";
    private static final int FIXED_PARTS = 4;

    public IssueRequestRecord {
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (epoch != null && epoch < 0) {
            throw new IllegalArgumentException("접수 epoch는 음수가 아니다");
        }
    }

    /** epoch 필드가 없는 옛 형식 기록 */
    public IssueRequestRecord(long memberId, Instant acceptedAt, Instant expiresAt, boolean published,
                              boolean compensated) {
        this(memberId, acceptedAt, expiresAt, null, published, compensated);
    }

    /** 새 접수 기록(표식 없음) */
    public static IssueRequestRecord accepted(long memberId, Instant acceptedAt, Instant expiresAt, long epoch) {
        return new IssueRequestRecord(memberId, acceptedAt, expiresAt, epoch, false, false);
    }

    /** 옛 형식 접수 기록(epoch 없음, 표식 없음) */
    public static IssueRequestRecord accepted(long memberId, Instant acceptedAt, Instant expiresAt) {
        return new IssueRequestRecord(memberId, acceptedAt, expiresAt, null, false, false);
    }

    /** 이벤트에 실을 epoch. 기록에 epoch가 없으면(옛 형식) {@code controlEpoch}다 */
    public long epochOr(long controlEpoch) {
        return epoch != null ? epoch : controlEpoch;
    }

    /**
     * requests 값을 읽는다.
     *
     * epoch 필드는 만료 시각 바로 뒤({@code e} + 숫자)에만 올 수 있다. 없으면 옛 형식으로 읽는다.
     *
     * @throws IllegalArgumentException 형식이 틀렸다(필드 수, 숫자 아님, ACCEPTED 아님, epoch 필드 위치·값, 모르는 표식,
     *                                  같은 표식 두 번)
     */
    public static IssueRequestRecord parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("접수 기록이 비어 있다");
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length < FIXED_PARTS || !ACCEPTED.equals(parts[2])) {
            throw new IllegalArgumentException("접수 기록 형식이 아니다");
        }
        long memberId = parseUnsigned(parts[0]);
        Instant acceptedAt = Instant.ofEpochMilli(parseUnsigned(parts[1]));
        Instant expiresAt = Instant.ofEpochMilli(parseUnsigned(parts[3]));
        Long epoch = null;
        int markStart = FIXED_PARTS;
        if (parts.length > FIXED_PARTS && parts[FIXED_PARTS].startsWith(EPOCH_PREFIX)) {
            epoch = parseUnsigned(parts[FIXED_PARTS].substring(EPOCH_PREFIX.length()));
            markStart++;
        }
        boolean published = false;
        boolean compensated = false;
        for (int i = markStart; i < parts.length; i++) {
            switch (parts[i]) {
                case PUBLISHED -> {
                    requireFirst(published);
                    published = true;
                }
                case COMPENSATED -> {
                    requireFirst(compensated);
                    compensated = true;
                }
                default -> throw new IllegalArgumentException("모르는 접수 기록 표식이다");
            }
        }
        return new IssueRequestRecord(memberId, acceptedAt, expiresAt, epoch, published, compensated);
    }

    /** requests 값으로 쓴다. epoch가 없으면 epoch 필드를 쓰지 않는다(옛 형식). 표식은 PUBLISHED, COMPENSATED 순서다 */
    public String format() {
        StringBuilder value = new StringBuilder()
                .append(memberId).append(SEPARATOR)
                .append(acceptedAt.toEpochMilli()).append(SEPARATOR)
                .append(ACCEPTED).append(SEPARATOR)
                .append(expiresAt.toEpochMilli());
        if (epoch != null) {
            value.append(SEPARATOR).append(EPOCH_PREFIX).append(epoch);
        }
        if (published) {
            value.append(SEPARATOR).append(PUBLISHED);
        }
        if (compensated) {
            value.append(SEPARATOR).append(COMPENSATED);
        }
        return value.toString();
    }

    private static long parseUnsigned(String part) {
        if (part.isEmpty() || part.length() > 19 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("접수 기록의 숫자 필드가 아니다");
        }
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("접수 기록의 숫자 필드가 너무 크다", e);
        }
    }

    private static void requireFirst(boolean alreadySeen) {
        if (alreadySeen) {
            throw new IllegalArgumentException("같은 접수 기록 표식이 두 번 있다");
        }
    }
}
