package com.hapbang.member.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.MemberEmail;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.out.EmailSender;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 가입 이메일 인증 (#557). 이메일 가입 전에 메일로 받은 6자리 코드로 이메일 주인인지 확인한다. 구글 가입·초기 계정·관리자 계정은 거치지 않는다.
 * <ul>
 *     <li>가입 여부를 응답으로 알려 주지 않는다 (#557 결정). 이미 가입된 이메일이어도 같은 200을 주고, 코드 대신 "이미 가입된 이메일" 안내 메일을
 *     보낸다. 메일함 주인만 가입 여부를 안다. 가입 API는 인증 토큰부터 보고, 토큰은 가입되지 않은 이메일에만 나가므로 가입 API로도 알 수 없다.
 *     제한(1분 간격·IP·이메일·전체)은 가입 여부와 상관없이 똑같이 센다.</li>
 *     <li>남용 막기 (#557 결정): 같은 이메일 1분에 한 번, 한 IP 1시간 10통, 한 이메일 하루 3통, 앱 전체 하루 300통.
 *     Gmail 하루 발송 한도(약 500통) 소진, 남의 메일함 스팸·메일 폭탄, 코드 맞히기를 막는다.
 *     IP가 내부 주소(사설·루프백 등)면 진짜 접속 IP를 모르는 것이라 IP 제한을 건너뛰고 경고만 남긴다. 모든 사람이 한 IP로 보여
 *     가입 전체가 막히는 일을 피하려는 것이다(운영에서 진짜 IP가 오는지는 배포 뒤 확인한다, docs/deploy/gateway.md).</li>
 *     <li>메일을 못 보내면 저장한 코드와 재발송 간격을 지워 바로 다시 받을 수 있게 하고 503을 준다. IP 발송 수는 그대로 센다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberEmailVerificationUseCase {

    static final String SUBJECT = "[합방] 이메일 인증 코드";
    static final String ALREADY_REGISTERED_SUBJECT = "[합방] 이미 가입된 이메일이에요";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailVerificationStore emailVerificationStore;
    private final EmailSender emailSender;

    /**
     * 가입 인증 코드를 메일로 보낸다. 트랜잭션 없이 부른다(메일 발송을 DB 트랜잭션 안에 두지 않는다).
     * 제한은 싼 것부터 본다: 같은 이메일 1분 간격 → IP 1시간 10통 → 이메일 하루 3통 → 전체 하루 300통.
     * 1분 간격에 막힌 요청(연타)은 IP·이메일·전체 수를 쓰지 않는다. 뒤 제한에 막히면 1분 간격을 지워 되돌린다
     */
    public void sendSignUpCode(String email, String clientIp) {
        String normalizedEmail = MemberEmail.normalize(email);
        if (!emailVerificationStore.tryStartResendCooldown(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.EMAIL_CODE_RESEND_TOO_SOON);
        }
        try {
            checkSendLimits(normalizedEmail, clientIp);
        } catch (MemberException e) {
            emailVerificationStore.clearResendCooldown(normalizedEmail);
            throw e;
        }
        try {
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                // 가입 여부는 응답으로 알려 주지 않고, 메일함 주인에게만 안내한다
                emailSender.send(normalizedEmail, ALREADY_REGISTERED_SUBJECT, alreadyRegisteredBody());
            } else {
                String code = newCode();
                emailVerificationStore.saveCode(normalizedEmail, code);
                emailSender.send(normalizedEmail, SUBJECT, body(code));
            }
        } catch (RuntimeException e) {
            log.warn("가입 인증 메일을 보내지 못했습니다.", e);
            emailVerificationStore.deleteCode(normalizedEmail);
            emailVerificationStore.clearResendCooldown(normalizedEmail);
            throw new MemberException(MemberErrorCode.EMAIL_SEND_FAILED, e);
        }
    }

    /** IP·이메일·전체 발송 수를 센다. 하나라도 넘으면 예외 (이미 센 수는 되돌리지 않는다) */
    private void checkSendLimits(String normalizedEmail, String clientIp) {
        if (isPublicAddress(clientIp)) {
            if (!emailVerificationStore.tryCountIp(clientIp)) {
                throw new MemberException(MemberErrorCode.EMAIL_CODE_TOO_MANY_REQUESTS);
            }
        } else {
            log.warn("가입 인증 코드 요청의 IP가 내부 주소라 IP당 발송 제한을 건너뜁니다. 진짜 접속 IP가 오는지 확인해야 합니다. ip={}",
                    clientIp);
        }
        if (!emailVerificationStore.tryCountEmailDaily(normalizedEmail)) {
            throw new MemberException(MemberErrorCode.EMAIL_CODE_DAILY_LIMIT);
        }
        if (!emailVerificationStore.tryCountGlobalDaily()) {
            log.error("가입 인증 메일이 앱 전체 하루 상한({}통)에 닿았습니다. 남용인지 확인해야 합니다.",
                    EmailVerificationPolicy.GLOBAL_DAILY_LIMIT);
            throw new MemberException(MemberErrorCode.EMAIL_SEND_UNAVAILABLE);
        }
    }

    /**
     * 코드를 확인하고 맞으면 인증 토큰을 준다. 틀리면 400 INVALID_EMAIL_CODE, 5번째로 틀리거나 코드가 없으면(10분 지남, 이미 씀)
     * 400 EMAIL_CODE_EXPIRED(다시 받아야 함)
     */
    public String verifySignUpCode(String email, String code) {
        String normalizedEmail = MemberEmail.normalize(email);
        return switch (emailVerificationStore.checkCode(normalizedEmail, code, EmailVerificationPolicy.MAX_CODE_ATTEMPTS)) {
            case MATCHED -> emailVerificationStore.issueToken(normalizedEmail);
            case WRONG -> throw new MemberException(MemberErrorCode.INVALID_EMAIL_CODE);
            case ATTEMPTS_EXCEEDED, NOT_FOUND -> throw new MemberException(MemberErrorCode.EMAIL_CODE_EXPIRED);
        };
    }

    /**
     * 가입할 이메일이 인증됐는지 확인한다 (가입 API 입구에서만 부른다. 초기 계정·관리자 계정은 거치지 않는다).
     * 토큰이 없거나, 만료됐거나, 다른 이메일의 토큰이면 400 EMAIL_NOT_VERIFIED
     */
    public void requireVerified(String email, String token) {
        String normalizedEmail = MemberEmail.normalize(email);
        boolean verified = emailVerificationStore.findTokenEmail(token).filter(normalizedEmail::equals).isPresent();
        if (!verified) {
            throw new MemberException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    /**
     * 가입에 쓴 인증 토큰을 지운다. 트랜잭션 안이면 커밋된 뒤에 지운다(가입이 실패하면 토큰을 그대로 둬 인증을 다시 하지 않게).
     * 지우다 실패해도(Redis 오류) 가입은 이미 끝났으므로 오류로 돌려주지 않고 로그만 남긴다. 토큰은 30분 뒤 저절로 사라지고,
     * 그사이 다시 써도 그 이메일은 이미 가입돼 409 DUPLICATE_EMAIL이다
     */
    public void consumeAfterSignUp(String token) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTokenQuietly(token);
                }
            });
            return;
        }
        deleteTokenQuietly(token);
    }

    private void deleteTokenQuietly(String token) {
        try {
            emailVerificationStore.deleteToken(token);
        } catch (RuntimeException e) {
            log.warn("가입에 쓴 인증 토큰을 지우지 못했습니다. 30분 뒤 저절로 사라집니다.", e);
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

    static String body(String code) {
        return """
                합방 가입 인증 코드는 %s 입니다.
                %d분 안에 가입 화면에 입력해 주세요.

                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(code, EmailVerificationPolicy.CODE_TTL.toMinutes());
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
