package com.hapbang.auxiliary.notification.domain;

import java.time.Instant;

import org.springframework.util.Assert;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 이벤트를 받아 알림 모듈에 복제한 회원 정보(ND-018). ID는 원본 회원 ID를 그대로 쓰고, 버전 규칙은 {@link ReplicaUser}를 따른다.
 * 받는 사람의 탈퇴 여부·역할·닉네임을 여기서 읽는다.
 * <p>
 * 파기 표시(ND-054): {@code UserMaskedEvent}를 받으면 파기 시각을 남기고 닉네임을 {@code 탈퇴회원#{회원ID}}, 상태를 탈퇴로 둔다.
 * 파기된 복제본은 뒤에 오는 가입·변경 이벤트로 바뀌지 않는다. 복제본이 없던 회원은 역할 없이 파기 상태로 만든다.
 */
@Entity
@Table(name = "notification_user", schema = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationUser extends ReplicaUser {

    /** 파기 시각. 없으면 파기되지 않았다 */
    @Getter
    private Instant maskedAt;

    private NotificationUser(UserDto user) {
        super(user);
    }

    public static NotificationUser create(UserDto user) {
        validate(user);
        return new NotificationUser(user);
    }

    /** 복제본이 없는 회원의 파기: 파기 상태로 만든다. {@code UserMaskedEvent}에는 역할이 없어 역할은 비운다(ND-054) */
    public static NotificationUser createMasked(long userId, Instant maskedAt) {
        Assert.notNull(maskedAt, "파기 시각이 필요합니다.");
        NotificationUser user = new NotificationUser(
                new UserDto(userId, NotificationPayload.maskedNickname(userId), null, UserStatus.WITHDRAWN, null));
        user.maskedAt = maskedAt;
        return user;
    }

    /** 있는 복제본의 파기. 이미 파기됐으면 처음 시각을 둔다 */
    public void markMasked(Instant now) {
        if (maskedAt != null) {
            return;
        }
        maskedAt = now;
        changeProfile(NotificationPayload.maskedNickname(getId()), getRole(), UserStatus.WITHDRAWN);
    }

    public boolean isMasked() {
        return maskedAt != null;
    }

    /** 회원 변경 반영. 파기된 복제본은 바꾸지 않는다(늦게 온 옛 변경으로 닉네임이 되살아나지 않는다) */
    @Override
    public void syncFrom(UserDto user) {
        validate(user);
        if (isMasked()) {
            return;
        }
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
