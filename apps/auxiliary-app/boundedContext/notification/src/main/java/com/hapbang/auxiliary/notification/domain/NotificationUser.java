package com.hapbang.auxiliary.notification.domain;

import org.springframework.util.Assert;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 회원 이벤트를 받아 알림 모듈에 복제한 회원 정보(ND-018). ID는 원본 회원 ID를 그대로 쓰고, 버전 규칙은 {@link ReplicaUser}를 따른다.
 * 받는 사람의 탈퇴 여부·역할·닉네임을 여기서 읽는다(알림 생성은 N2 이후).
 */
@Entity
@Table(name = "notification_user", schema = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationUser extends ReplicaUser {

    private NotificationUser(UserDto user) {
        super(user);
    }

    public static NotificationUser create(UserDto user) {
        validate(user);
        return new NotificationUser(user);
    }

    @Override
    public void syncFrom(UserDto user) {
        validate(user);
        super.syncFrom(user);
    }

    /** 복제할 수 있는 회원 정보인지. 동기화 서비스가 도메인 호출 전에 같은 기준으로 검사한다 */
    public static boolean isReplicable(UserDto user) {
        return user != null && user.id() != null && user.nickname() != null && !user.nickname().isBlank()
                && user.role() != null && user.status() != null;
    }

    private static void validate(UserDto user) {
        Assert.state(isReplicable(user), "복제할 수 없는 회원 정보입니다.");
    }
}
