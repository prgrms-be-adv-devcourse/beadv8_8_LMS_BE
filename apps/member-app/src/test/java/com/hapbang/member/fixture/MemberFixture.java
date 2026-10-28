package com.hapbang.member.fixture;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원 테스트 픽스처: 역할·상태별 회원을 만든다 (저장은 테스트가 한다). 이메일은 겹치지 않게 번호를 붙인다 */
public final class MemberFixture {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private MemberFixture() {
    }

    /** 정상 유저 */
    public static User user() {
        return member(Role.USER, UserStatus.ACTIVE);
    }

    /** 정상 회원 (역할 지정) */
    public static User member(Role role) {
        return member(role, UserStatus.ACTIVE);
    }

    /** 역할·상태 지정 (탈퇴·정지 회원은 도메인 메서드가 아직 없어 필드로 넣는다) */
    public static User member(Role role, UserStatus status) {
        int number = SEQUENCE.incrementAndGet();
        User user = User.join("member" + number + "@example.com", "회원" + number);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}
