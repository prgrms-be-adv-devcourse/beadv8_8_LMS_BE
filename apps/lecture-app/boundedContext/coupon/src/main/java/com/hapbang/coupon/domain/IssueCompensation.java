package com.hapbang.coupon.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 영구 거절한 발급 요청의 Redis 수량 보상 결과(compensate.lua 반환값 해석). 보상은 DB에 거절을 확정한 뒤 요청마다 한 번만 한다.
 * <ul>
 *     <li>보상 표식: requests 값 끝의 {@code |COMPENSATED}. 표식이 있으면 다시 복구하지 않는다(DB 보상 기록 전에 종료돼도 중복 복구가
 *     없다).</li>
 *     <li>epoch: 요청의 epoch가 issue-control의 현재 epoch와 다르면(또는 issue-control이 없으면) 재구축된 새 재고라 표식만 남기고
 *     재고·회원 기록은 바꾸지 않는다.</li>
 *     <li>접수 기록이 없으면 이미 복구됐는지 알 수 없으므로 아무것도 바꾸지 않는다(대사·복구가 맡는다).</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum IssueCompensation {

    /** 표식을 남기고 재고 +1, 회원 기록을 지웠다 */
    RESTORED(1, true),
    /** 이미 보상 표식이 있어 아무것도 바꾸지 않았다 */
    ALREADY_COMPENSATED(2, false),
    /** epoch가 다르거나 issue-control이 없어 표식만 남겼다(재고·회원 기록 불변) */
    MARKED_ONLY(3, false),
    /** 접수 기록이 없어 아무것도 바꾸지 않았다 */
    NO_RECORD(0, false),
    /** 접수 기록의 회원이 요청의 회원과 달라 아무것도 바꾸지 않았다 */
    MEMBER_MISMATCH(-4, false);

    private final long luaCode;
    /** 재고를 되돌렸는가 */
    private final boolean stockRestored;

    /**
     * compensate.lua 반환값을 해석한다.
     *
     * @throws CouponDomainException 알 수 없는 값(-9 인수·key 타입·접수 기록 형식 오류 포함). 성공으로 보지 않는다
     */
    public static IssueCompensation fromLua(long code) {
        for (IssueCompensation value : values()) {
            if (value.luaCode == code) {
                return value;
            }
        }
        throw new CouponDomainException(CouponDomainError.UNKNOWN_LUA_RESULT, "보상 결과를 해석하지 못했습니다. code=" + code);
    }

    /** 보상을 끝냈는가(다시 시도할 필요가 없다). 접수 기록이 없거나 회원이 다르면 사람이 확인해야 한다 */
    public boolean isSettled() {
        return this == RESTORED || this == ALREADY_COMPENSATED || this == MARKED_ONLY;
    }
}
