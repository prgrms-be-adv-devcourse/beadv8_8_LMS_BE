package com.hapbang.member.app;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.ClientIpKey;
import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.PasswordLoginLockStore;
import com.hapbang.member.out.PasswordLoginLockStore.FailureResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 이메일·비밀번호 로그인 (#499). 토큰 발급은 Facade가 {@link MemberTokenUseCase}로 한다.
 * <ul>
 *     <li>없는 이메일과 틀린 비밀번호는 같은 오류(INVALID_CREDENTIALS)로 답해 가입된 이메일인지 알려주지 않는다.
 *     없는 이메일도 가짜 해시로 같은 계산을 해서 응답 시간으로도 구별되지 않게 한다</li>
 *     <li>비밀번호를 비교하기 전에 IP 한도(#506, {@link LoginRateLimit})를 센다</li>
 *     <li>연속으로 틀리면 잠근다 (없는 이메일도 똑같이 센다). 남이 피해자 이메일로 틀려 피해자를 막지 못하게 (이메일, IP)마다 잠그고,
 *     IP를 여러 개 쓰는 대입은 이메일 전체 상한으로 막되 최근 로그인에 성공한 IP는 빼 준다({@link PasswordLoginLockStore}, #506)</li>
 *     <li>비밀번호가 맞아도 정지·탈퇴 회원은 로그인할 수 없다. 복구 기한 안의 탈퇴 회원은 복구 티켓을 준다({@link MemberLoginGuard}, #593)</li>
 * </ul>
 * 지표 {@code hapbang.member.login.locked{scope}}: 잠긴 수. scope = {@code email_ip}((이메일, IP) 잠금), {@code email}(이메일 전체 잠금).
 */
@Slf4j
@Service
public class MemberPasswordLoginUseCase {

    static final String LOCKED_METRIC = "hapbang.member.login.locked";

    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordLoginLockStore lockStore;
    private final MemberLoginGuard memberLoginGuard;
    private final LoginRateLimit loginRateLimit;
    private final Counter emailIpLocked;
    private final Counter emailLocked;
    /** 없는 이메일일 때 비교할 해시 (응답 시간을 맞추려고 실제 해시와 같은 비용으로 만든다) */
    private final String dummyHash;

    @Autowired
    public MemberPasswordLoginUseCase(PasswordCredentialRepository passwordCredentialRepository,
                                      PasswordEncoder passwordEncoder, PasswordLoginLockStore lockStore,
                                      MemberLoginGuard memberLoginGuard, LoginRateLimit loginRateLimit,
                                      MeterRegistry registry) {
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.lockStore = lockStore;
        this.memberLoginGuard = memberLoginGuard;
        this.loginRateLimit = loginRateLimit;
        this.emailIpLocked = Counter.builder(LOCKED_METRIC).tag("scope", "email_ip")
                .description("비밀번호 로그인 연속 실패로 잠근 수").register(registry);
        this.emailLocked = Counter.builder(LOCKED_METRIC).tag("scope", "email")
                .description("비밀번호 로그인 연속 실패로 잠근 수").register(registry);
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-1");
    }

    /**
     * @param clientIp {@code request.getRemoteAddr()}
     */
    public User login(String email, String password, String clientIp) {
        loginRateLimit.acquire(clientIp);
        String normalizedEmail = MemberEmail.normalize(email);
        // 공인 주소가 아니면 IP를 모르는 것으로 보고 모르는 IP 하나로 센다(성공한 IP로 기억하지도 않는다)
        ClientIpKey ipKey = ClientIpKey.from(clientIp).orElse(null);
        if (lockStore.check(normalizedEmail, ipKey) != PasswordLoginLockStore.Lock.NONE) {
            throw new MemberException(MemberErrorCode.LOGIN_LOCKED);
        }
        // 마스킹으로 지운 비밀번호(#594)는 없는 것과 같다. 비교기에 넘기면 해시 형식이 아니라 오류(500)가 난다
        Optional<PasswordCredential> credential = passwordCredentialRepository.findByUserEmail(normalizedEmail)
                .filter(found -> !found.isWiped());
        String hash = credential.map(PasswordCredential::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password == null ? "" : password, hash);
        if (credential.isEmpty() || !matches) {
            recordFailure(normalizedEmail, ipKey);
            throw new MemberException(MemberErrorCode.INVALID_CREDENTIALS);
        }
        // 비밀번호가 맞았으니 이 IP의 실패 횟수는 지우고 성공한 IP로 기억한다 (복구할까요?·정지로 끝나도 같다)
        lockStore.recordSuccess(normalizedEmail, ipKey);
        User user = credential.get().getUser();
        memberLoginGuard.check(user);
        return user;
    }

    private void recordFailure(String normalizedEmail, ClientIpKey ipKey) {
        FailureResult result = lockStore.recordFailure(normalizedEmail, ipKey);
        if (result.emailIpLocked()) {
            emailIpLocked.increment();
            log.warn("비밀번호 로그인 연속 실패로 잠금 scope=email_ip");
        }
        if (result.emailLocked()) {
            emailLocked.increment();
            log.warn("비밀번호 로그인 이메일 전체 실패 상한으로 잠금 scope=email");
        }
    }
}
