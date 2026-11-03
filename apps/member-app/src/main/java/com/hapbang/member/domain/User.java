package com.hapbang.member.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.SourceUser;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 원본 */
@Entity
@Table(name = "users", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends SourceUser {

    /** 탈퇴 뒤 복구할 수 있는 기간 (#593). 30일 뒤 식별 정보 마스킹(#594)도 이 기간을 쓴다 (policy.md 삭제 정책) */
    public static final Duration RESTORE_PERIOD = Duration.ofDays(30);

    @Column(unique = true)
    private String email;

    private String profileImageKey;

    private LocalDateTime deletedAt;

    /** 낙관적 락 버전. 동시에 수정하면 늦게 저장하는 쪽이 실패한다 (값은 JPA가 관리, MemberSupport.flush가 409로 바꾼다) */
    @Version
    private Long version;

    private User(String email, String nickname) {
        super(Nickname.validate(nickname), Role.USER, UserStatus.ACTIVE);
        this.email = email;
    }

    /** 신규 가입 (USER, ACTIVE, 프로필 이미지 없음) */
    public static User join(String email, String nickname) {
        return new User(email, nickname);
    }

    /** 닉네임 변경 (닉네임 규칙 검증) */
    public void changeNickname(String nickname) {
        changeProfile(Nickname.validate(nickname), getRole(), getStatus());
    }

    /** 프로필 이미지 변경 (S3 키, null이면 삭제). API는 S3 공통 설정 이후 */
    public void changeProfileImage(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    /** 역할 변경 (승급·초기 계정·관리자 계정 생성) */
    public void changeRole(Role role) {
        changeProfile(getNickname(), role, getStatus());
    }

    /**
     * 탈퇴 (#593). 상태와 탈퇴 시각을 이 메서드에서만 함께 바꿔 어긋나지 않게 한다. 정상 회원만 탈퇴한다
     * (정지 회원 정책은 정지 기능을 만들 때 정한다). 개인정보는 복구를 위해 그대로 두고, 30일 뒤 마스킹한다
     */
    public void withdraw(LocalDateTime now) {
        if (!isActive()) {
            throw new IllegalStateException("정상 회원만 탈퇴할 수 있습니다. status=" + getStatus());
        }
        changeProfile(getNickname(), getRole(), UserStatus.WITHDRAWN);
        this.deletedAt = now;
    }

    /** 복구할 수 있는지: 탈퇴하고 {@link #RESTORE_PERIOD}가 지나기 전 */
    public boolean canRestore(LocalDateTime now) {
        return isWithdrawn() && deletedAt != null && now.isBefore(deletedAt.plus(RESTORE_PERIOD));
    }

    /** 복구 (#593): 정상 회원으로 되돌린다. 같은 계정 그대로이고 기록·날짜는 바뀌지 않는다 */
    public void restore(LocalDateTime now) {
        if (!canRestore(now)) {
            throw new IllegalStateException("복구할 수 없는 회원입니다. status=" + getStatus() + ", deletedAt=" + deletedAt);
        }
        changeProfile(getNickname(), getRole(), UserStatus.ACTIVE);
        this.deletedAt = null;
    }

    /** 다른 모듈에 넘길 회원 정보. 버전은 flush 뒤에 불러야 올라간 값이 담긴다 (MemberSupport.flush) */
    public UserDto toDto() {
        return new UserDto(getId(), getNickname(), getRole(), getStatus(), version);
    }
}
