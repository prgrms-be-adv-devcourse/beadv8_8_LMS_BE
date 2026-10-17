package com.hapbang.shared.member.domain;

import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
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

    /** 마지막으로 반영한 회원 원본 버전. 이보다 크지 않은 정보는 무시한다 (null: 아직 버전 있는 정보를 받은 적 없음) */
    private Long memberVersion;

    /** 복제본 행의 낙관적 락. 같은 회원 정보를 동시에 반영하면 늦은 쪽이 실패해, 재처리 때 버전 비교로 정리된다 */
    @Version
    private Long version;

    /** 회원 정보로 생성 */
    protected ReplicaUser(UserDto user) {
        super(user.nickname(), user.role(), user.status());
        this.id = user.id();
        this.memberVersion = user.version();
    }

    /** 원본 회원 변경 내용 반영. 이미 반영한 버전보다 크지 않으면(늦게 온·중복된 정보) 무시한다 */
    public void syncFrom(UserDto user) {
        if (!id.equals(user.id())) {
            throw new IllegalArgumentException("다른 회원의 정보로 갱신할 수 없습니다.");
        }
        if (isAlreadyApplied(user.version())) {
            return;
        }
        changeProfile(user.nickname(), user.role(), user.status());
        this.memberVersion = user.version();
    }

    /**
     * 이미 반영한 정보인지. 아직 버전을 모르면 무엇이든 반영한다.
     * 버전을 받은 뒤 온 버전 없는 정보는 버전 도입 전에 저장된 옛 이벤트(재처리)라 무시한다
     */
    private boolean isAlreadyApplied(Long incomingVersion) {
        if (memberVersion == null) {
            return false;
        }
        return incomingVersion == null || incomingVersion <= memberVersion;
    }
}
