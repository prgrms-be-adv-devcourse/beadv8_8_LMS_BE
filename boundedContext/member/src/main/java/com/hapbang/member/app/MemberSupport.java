package com.hapbang.member.app;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 회원 조회·역할 변경 공통 (없으면 MEMBER_NOT_FOUND) */
@Service
@RequiredArgsConstructor
public class MemberSupport {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /** 정상(ACTIVE) 회원만. 탈퇴·정지 회원은 없는 회원처럼 MEMBER_NOT_FOUND */
    public User findActiveUser(Long userId) {
        User user = findUser(userId);
        if (!user.isActive()) {
            throw new MemberException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
        return user;
    }

    /** 정상 인플루언서·인증된 인플루언서 목록 */
    public Page<User> findInfluencers(Pageable pageable) {
        return userRepository.findByRoleInAndStatus(List.of(Role.INFLUENCER, Role.VERIFIED_INFLUENCER),
                UserStatus.ACTIVE, pageable);
    }

    /**
     * 바뀐 회원 정보를 지금 DB에 반영한다. 다른 트랜잭션이 먼저 바꿨으면(@Version 충돌) MEMBER_CONCURRENT_UPDATE(409).
     * 커밋 때 충돌하면 500이 되므로, 회원을 바꾸는 곳은 이벤트 발행 전에 부른다
     */
    public void flush() {
        try {
            userRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new MemberException(MemberErrorCode.MEMBER_CONCURRENT_UPDATE);
        }
    }

    /** 역할을 바꾸고 UserModifiedEvent 발행 (다른 모듈 복제본 갱신, 커밋 뒤 세션 권한 갱신). 역할 변경은 반드시 여기로 */
    public void changeRole(User user, Role role) {
        user.changeRole(role);
        flush();
        eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
    }
}
