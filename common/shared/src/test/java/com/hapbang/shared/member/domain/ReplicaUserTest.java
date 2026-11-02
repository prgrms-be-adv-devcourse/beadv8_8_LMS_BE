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
    @DisplayName("더 새로운 회원 버전이면 반영하고 그 버전을 기억한다")
    void syncFrom_newerVersion() {
        TestReplicaUser user = new TestReplicaUser(dto("철수", Role.USER, 0L));

        user.syncFrom(dto("철수", Role.INFLUENCER, 1L));

        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getMemberVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("늦게 도착한 옛 버전은 무시한다 (v2 반영 뒤 v1 도착)")
    void syncFrom_olderVersion() {
        TestReplicaUser user = new TestReplicaUser(dto("철수", Role.USER, 0L));
        user.syncFrom(dto("영희", Role.INFLUENCER, 2L));

        user.syncFrom(dto("철수", Role.INFLUENCER, 1L));

        assertThat(user.getNickname()).isEqualTo("영희");
        assertThat(user.getMemberVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("같은 버전이 다시 오면(재처리) 무시한다")
    void syncFrom_sameVersion() {
        TestReplicaUser user = new TestReplicaUser(dto("철수", Role.USER, 0L));
        user.syncFrom(dto("영희", Role.USER, 1L));

        user.syncFrom(dto("다른값", Role.INFLUENCER, 1L));

        assertThat(user.getNickname()).isEqualTo("영희");
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("아직 버전을 모르면 버전 있는 정보·없는 정보 모두 반영한다 (기존 복제본, 버전 없는 테스트 정보)")
    void syncFrom_unknownVersion() {
        TestReplicaUser user = new TestReplicaUser(new UserDto(1L, "철수", Role.USER, UserStatus.ACTIVE));

        user.syncFrom(new UserDto(1L, "영희", Role.USER, UserStatus.ACTIVE));
        assertThat(user.getNickname()).isEqualTo("영희");
        assertThat(user.getMemberVersion()).isNull();

        user.syncFrom(dto("민수", Role.USER, 3L));
        assertThat(user.getNickname()).isEqualTo("민수");
        assertThat(user.getMemberVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("버전을 받은 뒤 온 버전 없는 정보는 무시한다 (버전 도입 전에 저장된 옛 이벤트의 재처리)")
    void syncFrom_versionlessAfterVersioned() {
        TestReplicaUser user = new TestReplicaUser(dto("철수", Role.INFLUENCER, 3L));

        user.syncFrom(new UserDto(1L, "옛닉네임", Role.USER, UserStatus.ACTIVE));

        assertThat(user.getNickname()).isEqualTo("철수");
        assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(user.getMemberVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("다른 회원 정보로 갱신하면 예외가 발생한다")
    void syncFrom_otherUser() {
        TestReplicaUser user = new TestReplicaUser(new UserDto(1L, "tester1", Role.USER, UserStatus.ACTIVE));

        assertThatThrownBy(() -> user.syncFrom(new UserDto(2L, "tester2", Role.USER, UserStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UserDto dto(String nickname, Role role, Long version) {
        return new UserDto(1L, nickname, role, UserStatus.ACTIVE, version);
    }
}
