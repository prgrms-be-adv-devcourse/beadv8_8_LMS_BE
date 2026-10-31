package com.hapbang.appearance.fixture;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 테스트용 회원 정보. 회원 모듈이 이벤트로 보내는 {@link UserDto}와 같은 모양이다.
 */
public final class AppearanceUserFixture {

    private AppearanceUserFixture() {
    }

    /** 출연 거래에 참여할 수 있는 인플루언서 */
    public static UserDto influencer(long id) {
        return user(id, Role.INFLUENCER, UserStatus.ACTIVE);
    }

    /** 출연 거래에 참여할 수 없는 일반 유저 */
    public static UserDto normalUser(long id) {
        return user(id, Role.USER, UserStatus.ACTIVE);
    }

    public static UserDto user(long id, Role role, UserStatus status) {
        return new UserDto(id, "user" + id, role, status);
    }
}
