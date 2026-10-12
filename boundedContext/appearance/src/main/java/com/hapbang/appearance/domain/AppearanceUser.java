package com.hapbang.appearance.domain;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 출연 모듈에 복제한 회원 정보. ID는 원본 회원 ID를 그대로 쓴다.
 * <p>
 * 회원 모듈의 이벤트로 만들고 갱신하며, 출연 모듈은 이 정보로 호스트·게스트 자격을 확인한다.
 * 닉네임·역할·상태와 판단 메서드({@code isActive}, {@code isInfluencerOrAbove})는 공통 부모 {@link ReplicaUser}에 있다.
 */
@Entity
@Table(name = "users", schema = "appearances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceUser extends ReplicaUser {

    private AppearanceUser(UserDto user) {
        super(user);
    }

    public static AppearanceUser from(UserDto user) {
        return new AppearanceUser(user);
    }

    /**
     * 호스트·게스트로 출연 거래(요청·모집·지원)에 참여할 수 있는지 확인한다.
     * <ol>
     *     <li>정상(ACTIVE) 상태가 아니면 {@code APPEARANCE_USER_NOT_ACTIVE} (탈퇴·정지)</li>
     *     <li>인플루언서 이상이 아니면 {@code NOT_INFLUENCER} (일반 유저, 관리자)</li>
     * </ol>
     */
    public void validateCanAppear() {
        if (!isActive()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        }
        if (!isInfluencerOrAbove()) {
            throw new AppearanceException(AppearanceErrorCode.NOT_INFLUENCER);
        }
    }
}
