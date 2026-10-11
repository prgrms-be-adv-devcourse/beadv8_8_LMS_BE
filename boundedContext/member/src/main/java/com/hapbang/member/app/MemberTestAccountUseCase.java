package com.hapbang.member.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/** 역할별 테스트 계정 (로컬 로그인용). 없으면 만들고 가입 이벤트를 발행한다 */
@Service
@RequiredArgsConstructor
public class MemberTestAccountUseCase {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public User findOrCreate(Role role) {
        return userRepository.findByEmail(email(role)).orElseGet(() -> create(role));
    }

    private User create(Role role) {
        User user = User.join(email(role), nickname(role));
        // 가입 이벤트 전에 역할을 정해야 다른 모듈 복제본에도 이 역할이 들어간다
        user.changeRole(role);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
        return user;
    }

    /** 실제로 존재할 수 없는 .local 도메인 */
    private String email(Role role) {
        String name = switch (role) {
            case USER -> "user";
            case INFLUENCER -> "influencer";
            case VERIFIED_INFLUENCER -> "verified";
            case ADMIN -> "admin";
        };
        return "test-" + name + "@hapbang.local";
    }

    private String nickname(Role role) {
        return switch (role) {
            case USER -> "테스트유저";
            case INFLUENCER -> "테스트인플루언서";
            case VERIFIED_INFLUENCER -> "테스트인증인플루언서";
            case ADMIN -> "테스트관리자";
        };
    }
}
