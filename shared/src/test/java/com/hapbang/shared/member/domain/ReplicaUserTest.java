package com.hapbang.shared.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.member.dto.UserDto;

class ReplicaUserTest {

    /** 테스트용 하위 클래스 */
    static class TestReplicaUser extends ReplicaUser {
        TestReplicaUser(UserDto user) {
            super(user);
        }
    }

    @Test
    @DisplayName("회원 정보로 생성하면 ID·닉네임·역할·상태가 복사된다")
    void create() {
        UserDto dto = new UserDto(1L, "tester1", Role.USER, UserStatus.ACTIVE);

        TestReplicaUser user = new TestReplicaUser(dto);

        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getNickname()).isEqualTo("tester1");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("같은 회원 정보로 갱신하면 닉네임·역할·상태가 바뀌고 ID는 유지된다")
    void syncFrom() {
        TestReplicaUser user = new TestReplicaUser(new UserDto(1L, "tester1", Role.USER, UserStatus.ACTIVE));

        user.syncFrom(new UserDto(1L, "tester2", Role.INFLUENCER, UserStatus.WITHDRAWN));

        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getNickname()).isEqualTo("tester2");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("다른 회원 정보로 갱신하면 예외가 발생한다")
    void syncFrom_otherUser() {
        TestReplicaUser user = new TestReplicaUser(new UserDto(1L, "tester1", Role.USER, UserStatus.ACTIVE));

        assertThatThrownBy(() -> user.syncFrom(new UserDto(2L, "tester2", Role.USER, UserStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
