package com.hapbang.billing.fixture;

import com.hapbang.billing.domain.BillingUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/** 지갑 회원(회원 복제본) 테스트 데이터 (#537). 충전·환불·환전 신청은 토큰 역할과 함께 이 복제본도 확인한다 */
public final class WalletMemberFixture {

    private WalletMemberFixture() {
    }

    /** 정상 상태의 지갑 회원 */
    public static BillingUser active(Long userId, Role role) {
        return member(userId, role, UserStatus.ACTIVE);
    }

    public static BillingUser member(Long userId, Role role, UserStatus status) {
        return BillingUser.from(new UserDto(userId, "회원" + userId, role, status, 1L));
    }
}
