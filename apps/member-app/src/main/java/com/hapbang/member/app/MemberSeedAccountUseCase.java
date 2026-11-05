package com.hapbang.member.app;

import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 역할별 초기 계정 (#520). 인플루언서·인증된 인플루언서·관리자를 하나씩 만들고, 비밀번호 로그인으로 쓴다 (유저는 가입하면 된다).
 * 운영 첫 관리자도 이 관리자 계정으로 대신한다. 같은 이메일의 회원이 있으면 건너뛰어, 비밀번호를 바꾼 뒤 재시작해도 되돌리지 않는다.
 * 다른 회원이 이미 그 닉네임을 쓰고 있으면 그 계정만 건너뛰고 경고 로그를 남긴다(#646). 앱 시작 중이라 409로 멈추면 앱이 뜨지 않는다.
 * <p>
 * 이메일·비밀번호는 코드에 둔다. 공개 레포라 운영에서도 누구나 이 계정(관리자 포함)으로 로그인할 수 있다.
 * HP·결제가 테스트라 받아들이기로 했다 (팀 결정, #520)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSeedAccountUseCase {

    static final String PASSWORD = "hapbang1234!";

    /** 역할마다 하나 */
    static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount(Role.INFLUENCER, "influencer@hapbang.local", "인플루언서"),
            new SeedAccount(Role.VERIFIED_INFLUENCER, "verified-influencer@hapbang.local", "인증된인플루언서"),
            new SeedAccount(Role.ADMIN, "admin@hapbang.local", "관리자"));

    private final UserRepository userRepository;
    private final MemberSignUpUseCase memberSignUpUseCase;

    /** 없는 계정만 만든다 */
    public void createAll() {
        for (SeedAccount account : ACCOUNTS) {
            if (userRepository.existsByEmailIgnoreCase(account.email())) {
                continue;
            }
            if (userRepository.existsByNicknameLower(account.nickname())) {
                // 닉네임은 그 닉네임을 쓰는 다른 회원의 정보라 로그에 남기지 않는다(docs/dev/logging.md "민감 정보", #582)
                log.warn("초기 계정 닉네임을 다른 회원이 쓰고 있어 만들지 않았습니다. role={}", account.role());
                continue;
            }
            User user = memberSignUpUseCase.register(account.email(), PASSWORD, account.nickname(), account.role());
            log.info("초기 계정 생성: role={}, userId={}", account.role(), user.getId());
        }
    }

    record SeedAccount(Role role, String email, String nickname) {
    }
}
