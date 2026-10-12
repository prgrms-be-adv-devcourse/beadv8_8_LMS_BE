package com.hapbang.member.app;

import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/** 역할별 테스트 계정 (로컬 로그인용). 같은 역할 여러 명은 번호로 구분한다 */
@Service
@RequiredArgsConstructor
public class MemberTestAccountUseCase {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 역할마다 만드는 개수 (호스트·게스트처럼 같은 역할 2명이 필요한 테스트용) */
    static final int ACCOUNTS_PER_ROLE = 2;

    /** 역할별 테스트 계정을 만든다. 이미 있으면 건너뛴다 */
    public void createAll() {
        for (Role role : Role.values()) {
            for (int number = 1; number <= ACCOUNTS_PER_ROLE; number++) {
                if (userRepository.findByEmail(email(role, number)).isEmpty()) {
                    create(role, number);
                }
            }
        }
    }

    public Optional<User> find(Role role, int number) {
        return userRepository.findByEmail(email(role, number));
    }

    /** 가입 이벤트도 발행한다 (다른 모듈 복제본 생성) */
    private void create(Role role, int number) {
        User user = User.join(email(role, number), nickname(role) + suffix(number));
        // 가입 이벤트 전에 역할을 정해야 다른 모듈 복제본에도 이 역할이 들어간다
        user.changeRole(role);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserJoinedEvent(user.toDto()));
    }

    /** 실제로 존재할 수 없는 .local 도메인. 예: test-influencer@…, test-influencer-2@… */
    private String email(Role role, int number) {
        String name = switch (role) {
            case USER -> "user";
            case INFLUENCER -> "influencer";
            case VERIFIED_INFLUENCER -> "verified";
            case ADMIN -> "admin";
        };
        return "test-" + name + (number == 1 ? "" : "-" + number) + "@hapbang.local";
    }

    private String nickname(Role role) {
        return switch (role) {
            case USER -> "테스트유저";
            case INFLUENCER -> "테스트인플루언서";
            case VERIFIED_INFLUENCER -> "테스트인증인플루언서";
            case ADMIN -> "테스트관리자";
        };
    }

    /** 1번은 번호를 붙이지 않는다 */
    private String suffix(int number) {
        return number == 1 ? "" : String.valueOf(number);
    }
}
