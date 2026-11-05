package com.hapbang.member.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.EmailVerificationPurpose;
import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.EmailSendQueue;
import com.hapbang.member.out.EmailSender;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 이메일 인증 (#557). 메일로 받은 6자리 인증 코드로 이메일 주인인지 확인한다. 용도는 이메일 가입과 비밀번호 재설정 두 가지다.
 * 구글 가입·초기 계정·관리자 계정은 거치지 않는다.
 * <ul>
 *     <li>가입 여부를 응답으로 알려 주지 않는다 (#557 결정). 코드 받기는 상황과 상관없이 같은 200이고, 메일함에 가는 것만 다르다.
 *     가입: 가입되지 않은 이메일 → 코드, 이미 가입된 이메일 → "이미 가입된 이메일" 안내.
 *     재설정: 비밀번호가 있는 정상 회원 → 코드, 구글로만 가입한 정상 회원 → "구글로 로그인" 안내, 그 밖(없는 아이디, 탈퇴·정지 회원) → 보내지 않음.
 *     가입 API는 인증 토큰부터 보고, 가입 토큰은 가입되지 않은 이메일에만 나가므로 가입 API로도 알 수 없다.</li>
 *     <li>남용 막기 (#557 결정): 같은 이메일 1분에 한 번, 한 IP 1시간 10통, 한 이메일 하루 5통, 앱 전체 하루 300통. 용도와 상관없이 합쳐 세고,
 *     메일을 보내지 않는 경우에도 똑같이 센다(응답·제한으로 가입 여부를 알아내지 못하게).
 *     Gmail 하루 발송 한도(약 500통) 소진, 남의 메일함 스팸·메일 폭탄, 코드 맞히기를 막는다.
 *     IP가 내부 주소(사설·루프백 등)면 진짜 접속 IP를 모르는 것이라 IP 제한을 건너뛰고 경고만 남긴다. 모든 사람이 한 IP로 보여
 *     가입 전체가 막히는 일을 피하려는 것이다(운영에서 진짜 IP가 오는지는 병합 전에 확인한다, docs/deploy/gateway.md).</li>
 *     <li>메일은 뒤에서 보낸다(EmailSendQueue). 응답 시간·코드가 메일을 보내는지와 상관없이 같아 가입 여부가 드러나지 않고,
 *     Gmail이 느려도 요청이 묶이지 않는다. 못 보내면 코드와 재발송 간격을 지워 바로 다시 받을 수 있게 한다. IP·이메일·전체 발송 수는 그대로 센다.</li>
 * </ul>
 */
@Slf4j
@Service
public class MemberEmailVerificationUseCase {

    static final String SUBJECT = "[합방] 이메일 인증 코드";
    static final String ALREADY_REGISTERED_SUBJECT = "[합방] 이미 가입된 이메일이에요";
    static final String PASSWORD_RESET_SUBJECT = "[합방] 비밀번호 재설정 인증 코드";
    static final String GOOGLE_ACCOUNT_SUBJECT = "[합방] 구글로 가입한 계정이에요";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final EmailVerificationStore emailVerificationStore;
    private final EmailSender emailSender;
    /** 메일을 뒤에서 보내는 대기열 (EmailSendQueue) */
    private final Executor emailSendExecutor;

    @Autowired
    MemberEmailVerificationUseCase(UserRepository userRepository,
                                   PasswordCredentialRepository passwordCredentialRepository,
                                   EmailVerificationStore emailVerificationStore, EmailSender emailSender,
                                   EmailSendQueue emailSendQueue) {
        this(userRepository, passwordCredentialRepository, emailVerificationStore, emailSender, emailSendQueue::submit);
    }

    /** 테스트가 메일 대기열 대신 실행 방법을 정할 때 쓴다 */
    MemberEmailVerificationUseCase(UserRepository userRepository,
                                   PasswordCredentialRepository passwordCredentialRepository,
                                   EmailVerificationStore emailVerificationStore, EmailSender emailSender,
                                   Executor emailSendExecutor) {
        this.userRepository = userRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.emailVerificationStore = emailVerificationStore;
        this.emailSender = emailSender;
        this.emailSendExecutor = emailSendExecutor;
    }

    /**
     * 인증 코드를 메일로 보낸다(용도별로 받는 메일이 다르다, 클래스 설명). 트랜잭션 없이 부른다.
     * 제한은 싼 것부터 본다: 같은 이메일 1분 간격 → IP 1시간 10통 → 이메일 하루 5통 → 전체 하루 300통.
     * 1분 간격에 막힌 요청(연타)은 IP·이메일·전체 수를 쓰지 않는다. 뒤 제한에 막히거나 오류가 나면(Redis 오류 포함) 1분 간격을
     * 지워 되돌린다. 이전 요청에서 받은 유효한 코드는 지우지 않는다.
     * <p>
     * 메일은 뒤에서 보낸다(대기열에 맡기고 바로 돌아온다). 그래서 응답 시간·코드가 메일을 보내는지(가입 여부)와 상관없이 같다.
     * 대기열이 가득 차면 503 EMAIL_SEND_UNAVAILABLE. 뒤에서 보내다 실패하면 코드와 1분 간격을 지워 바로 다시 받을 수 있게 하고
     * {@code [EMAIL_SEND_FAILED]} error 로그를 남긴다(사용자는 메일이 오지 않아 다시 받는다)
     */
    public void sendCode(String email, EmailVerificationPurpose purpose, String clientIp) {
        String normalizedEmail = MemberEmail.normalize(email);
        if (!emailVerificationStore.tryStartResendCooldown(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.EMAIL_CODE_RESEND_TOO_SOON);
        }
        try {
            checkSendLimits(normalizedEmail, clientIp);
            Optional<OutgoingMail> mail = switch (purpose) {
                case SIGN_UP -> Optional.of(signUpMail(normalizedEmail));
                case PASSWORD_RESET -> passwordResetMail(normalizedEmail);
            };
            mail.ifPresent(outgoing -> dispatch(normalizedEmail, purpose, outgoing));
        } catch (RuntimeException e) {
            // 1분 간격만 되돌린다. 코드는 이번 요청이 맡기지 못한 경우에만 dispatch가 지운다(이전에 받은 유효한 코드는 지우지 않는다)
            clearCooldownQuietly(normalizedEmail);
            throw e;
        }
    }

    /** 보낼 메일 한 통 */
    private record OutgoingMail(String subject, String text) {
    }

    /** 가입: 가입되지 않은 이메일이면 코드(저장), 이미 가입된 이메일이면 안내(가입 여부는 메일함 주인만 안다) */
    private OutgoingMail signUpMail(String normalizedEmail) {
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            return new OutgoingMail(ALREADY_REGISTERED_SUBJECT, alreadyRegisteredBody());
        }
        String code = newCode();
        emailVerificationStore.saveCode(normalizedEmail, EmailVerificationPurpose.SIGN_UP, code);
        return new OutgoingMail(SUBJECT, body(code));
    }

    /**
     * 재설정: 비밀번호가 있는 정상 회원이면 코드(저장), 구글로만 가입한 정상 회원이면 "구글로 로그인" 안내.
     * 없는 아이디, 탈퇴·정지 회원(#557 결정: 정상 회원만)에게는 보내지 않는다(회원이 아닌 사람 메일함에 메일이 가지 않게)
     */
    private Optional<OutgoingMail> passwordResetMail(String normalizedEmail) {
        Optional<User> member = userRepository.findByEmailIgnoreCase(normalizedEmail).filter(User::isActive);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        boolean hasPassword = passwordCredentialRepository.findByUserId(member.get().getId())
                .filter(credential -> !credential.isWiped())
                .isPresent();
        if (!hasPassword) {
            return Optional.of(new OutgoingMail(GOOGLE_ACCOUNT_SUBJECT, googleAccountBody()));
        }
        String code = newCode();
        emailVerificationStore.saveCode(normalizedEmail, EmailVerificationPurpose.PASSWORD_RESET, code);
        return Optional.of(new OutgoingMail(PASSWORD_RESET_SUBJECT, passwordResetBody(code)));
    }

    /** 메일을 대기열에 맡긴다. 가득 차면 503. 뒤에서 실패하면 코드·1분 간격을 지우고 error 로그 */
    private void dispatch(String normalizedEmail, EmailVerificationPurpose purpose, OutgoingMail mail) {
        try {
            emailSendExecutor.execute(() -> {
                try {
                    emailSender.send(normalizedEmail, mail.subject(), mail.text());
                } catch (RuntimeException e) {
                    log.error("[EMAIL_SEND_FAILED] 인증 메일을 보내지 못해 코드와 1분 간격을 지웠다 purpose={}", purpose, e);
                    cleanUpQuietly(normalizedEmail, purpose);
                }
            });
        } catch (RejectedExecutionException e) {
            deleteCodeQuietly(normalizedEmail, purpose);
            // 1분 간격은 sendCode가 되돌린다. 로그는 5xx를 처리하는 GlobalExceptionHandler가 원인과 함께 한 번 남긴다
            throw new MemberException(MemberErrorCode.EMAIL_SEND_UNAVAILABLE, e);
        }
    }

    /** 이번 요청이 저장한 코드와 1분 간격을 지운다(메일을 못 보냈을 때). 지우다 오류가 나도 원래 오류를 가리지 않게 로그만 남긴다 */
    private void cleanUpQuietly(String normalizedEmail, EmailVerificationPurpose purpose) {
        deleteCodeQuietly(normalizedEmail, purpose);
        clearCooldownQuietly(normalizedEmail);
    }

    private void deleteCodeQuietly(String normalizedEmail, EmailVerificationPurpose purpose) {
        try {
            emailVerificationStore.deleteCode(normalizedEmail, purpose);
        } catch (RuntimeException e) {
            log.warn("인증 코드를 지우지 못해 10분 뒤 저절로 만료되게 뒀다 purpose={}", purpose, e);
        }
    }

    private void clearCooldownQuietly(String normalizedEmail) {
        try {
            emailVerificationStore.clearResendCooldown(normalizedEmail);
        } catch (RuntimeException e) {
            log.warn("1분 간격을 지우지 못해 1분 뒤 저절로 풀리게 뒀다", e);
        }
    }

    /** IP·이메일·전체 발송 수를 센다. 하나라도 넘으면 예외 (이미 센 수는 되돌리지 않는다) */
    private void checkSendLimits(String normalizedEmail, String clientIp) {
        if (isPublicAddress(clientIp)) {
            if (!emailVerificationStore.tryCountIp(ipLimitKey(clientIp))) {
                throw new MemberException(MemberErrorCode.EMAIL_CODE_TOO_MANY_REQUESTS);
            }
        } else {
            log.warn("인증 코드 요청의 IP가 내부 주소라 IP당 발송 제한을 건너뛰었다. 진짜 접속 IP가 오는지 확인한다 ip={}",
                    clientIp);
        }
        if (!emailVerificationStore.tryCountEmailDaily(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.EMAIL_CODE_DAILY_LIMIT);
        }
        if (!emailVerificationStore.tryCountGlobalDaily()) {
            // 503은 GlobalExceptionHandler가 ERROR로 남긴다. 대기열이 가득 찬 503과 구분하려고 사유만 INFO로 남긴다
            log.info("앱 전체 하루 상한에 닿아 인증 메일을 거절했다 limit={}", EmailVerificationPolicy.GLOBAL_DAILY_LIMIT);
            throw new MemberException(MemberErrorCode.EMAIL_SEND_UNAVAILABLE);
        }
    }

    /**
     * 코드를 확인하고 맞으면 그 용도의 인증 토큰을 준다. 틀리면 400 INVALID_EMAIL_CODE, 5번째로 틀리거나 코드가 없으면
     * (10분 지남, 이미 씀, 받지 않음) 400 EMAIL_CODE_EXPIRED(다시 받아야 함)
     */
    public String verifyCode(String email, EmailVerificationPurpose purpose, String code) {
        String normalizedEmail = MemberEmail.normalize(email);
        return switch (emailVerificationStore.checkCode(normalizedEmail, purpose, code,
                EmailVerificationPolicy.MAX_CODE_ATTEMPTS)) {
            case MATCHED -> emailVerificationStore.issueToken(normalizedEmail, purpose);
            case WRONG -> throw new MemberException(MemberErrorCode.INVALID_EMAIL_CODE);
            case ATTEMPTS_EXCEEDED, NOT_FOUND -> throw new MemberException(MemberErrorCode.EMAIL_CODE_EXPIRED);
        };
    }

    /**
     * 이메일이 그 용도로 인증됐는지 확인한다 (가입·재설정 API 입구에서만 부른다. 초기 계정·관리자 계정은 거치지 않는다).
     * 토큰이 없거나, 만료됐거나, 다른 이메일·다른 용도의 토큰이면 400 EMAIL_NOT_VERIFIED
     */
    public void requireVerified(String email, EmailVerificationPurpose purpose, String token) {
        String normalizedEmail = MemberEmail.normalize(email);
        boolean verified = emailVerificationStore.findTokenEmail(token, purpose).filter(normalizedEmail::equals).isPresent();
        if (!verified) {
            throw new MemberException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    /**
     * 그 용도로 인증됐는지 확인하면서 토큰을 바로 지운다(비밀번호 재설정용). 같은 토큰으로 동시에 와도 한 요청만 통과한다.
     * 토큰이 없거나, 만료됐거나, 다른 이메일·다른 용도의 토큰이면 400 EMAIL_NOT_VERIFIED. 이 뒤에 실패하면 토큰은 이미 없으므로
     * 실패할 만한 검사(새 비밀번호 규칙 등)는 부르기 전에 끝낸다
     */
    public void takeVerified(String email, EmailVerificationPurpose purpose, String token) {
        String normalizedEmail = MemberEmail.normalize(email);
        boolean verified = emailVerificationStore.takeTokenEmail(token, purpose).filter(normalizedEmail::equals).isPresent();
        if (!verified) {
            throw new MemberException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    /**
     * 쓴 인증 토큰을 지운다. 트랜잭션 안이면 커밋된 뒤에 지운다(가입·재설정이 실패하면 토큰을 그대로 둬 인증을 다시 하지 않게).
     * 지우다 실패해도(Redis 오류) 일은 이미 끝났으므로 오류로 돌려주지 않고 로그만 남긴다. 토큰은 30분 뒤 저절로 사라진다
     * (가입은 다시 써도 409 DUPLICATE_EMAIL, 재설정은 다시 쓰면 그 사이 비밀번호를 한 번 더 바꿀 수 있지만 메일함 주인만 가진 토큰이다)
     */
    public void consumeAfterUse(String token, EmailVerificationPurpose purpose) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTokenQuietly(token, purpose);
                }
            });
            return;
        }
        deleteTokenQuietly(token, purpose);
    }

    private void deleteTokenQuietly(String token, EmailVerificationPurpose purpose) {
        try {
            emailVerificationStore.deleteToken(token, purpose);
        } catch (RuntimeException e) {
            log.warn("쓴 인증 토큰을 지우지 못해 30분 뒤 저절로 사라지게 뒀다 purpose={}", purpose, e);
        }
    }

    /** 6자리 숫자 (앞자리 0 포함) */
    static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String alreadyRegisteredBody() {
        return """
                이 이메일은 이미 합방에 가입되어 있어요.
                가입할 때 쓴 방법(이메일과 비밀번호, 또는 구글)으로 로그인해 주세요.

                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """;
    }

    static String googleAccountBody() {
        return """
                이 이메일은 구글로 가입한 합방 계정이라 비밀번호가 없어요.
                로그인 화면에서 구글로 로그인해 주세요.

                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """;
    }

    static String passwordResetBody(String code) {
        return """
                합방 비밀번호 재설정 인증 코드는 %s 입니다.
                %d분 안에 비밀번호 재설정 화면에 입력해 주세요.

                직접 요청하지 않았다면 이 메일은 무시해 주세요. 비밀번호는 바뀌지 않아요.
                """.formatted(code, EmailVerificationPolicy.CODE_TTL.toMinutes());
    }

    static String body(String code) {
        return """
                합방 가입 인증 코드는 %s 입니다.
                %d분 안에 가입 화면에 입력해 주세요.

                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(code, EmailVerificationPolicy.CODE_TTL.toMinutes());
    }

    /**
     * IP 제한을 세는 단위. IPv4는 주소 그대로, IPv6는 앞 64비트(/64)다. IPv6는 보통 한 사용자·회선에 /64 묶음을 주므로
     * 주소 하나씩 세면 그 안에서 주소를 바꿔 가며 제한을 피할 수 있다(#557 리뷰). 공인 주소로 확인된 값만 넘긴다
     */
    public static String ipLimitKey(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) {
                return ip;
            }
            StringBuilder prefix = new StringBuilder("v6/64:");
            for (int i = 0; i < 8; i += 2) {
                prefix.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
                if (i < 6) {
                    prefix.append(':');
                }
            }
            return prefix.toString();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /**
     * 인터넷 주소인지. 사설(10/8, 172.16/12, 192.168/16, fc00::/7)·루프백·링크 로컬·와일드카드면 false.
     * 숫자 주소만 받는다(이름을 넘기면 DNS 조회를 하지 않고 false)
     */
    static boolean isPublicAddress(String ip) {
        // 숫자 IPv4이거나 콜론이 있는 IPv6만 주소로 본다. 그 밖의 값(이름)은 InetAddress가 DNS를 조회하므로 넘기지 않는다
        boolean ipv4 = ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = ip != null && ip.contains(":") && ip.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
            return !(address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || uniqueLocalV6);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
