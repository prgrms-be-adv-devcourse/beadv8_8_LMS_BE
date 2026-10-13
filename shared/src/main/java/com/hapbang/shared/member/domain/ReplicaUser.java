package com.hapbang.shared.member.domain;

import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 복제 회원 부모 (ID는 원본 회원 ID 사용) */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ReplicaUser extends BaseUser {

    @Id
    private Long id;

    /** 회원 정보로 생성 */
    protected ReplicaUser(UserDto user) {
        super(user.nickname(), user.role(), user.status());
        this.id = user.id();
    }

    /** 원본 회원 변경 내용 반영 */
    public void syncFrom(UserDto user) {
        if (!id.equals(user.id())) {
            throw new IllegalArgumentException("다른 회원의 정보로 갱신할 수 없습니다.");
        }
        changeProfile(user.nickname(), user.role(), user.status());
    }
}
