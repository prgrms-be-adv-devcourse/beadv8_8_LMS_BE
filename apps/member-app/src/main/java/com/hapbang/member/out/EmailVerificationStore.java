package com.hapbang.member.out;

import java.util.Optional;

import com.hapbang.member.domain.EmailVerificationPurpose;

/**
 * 가입 이메일 인증 저장소 (#557). 인증 코드, 같은 이메일 재발송 간격, IP당 발송 횟수를 둔다.
 * 이메일·IP는 원문 대신 해시로 키를 만든다
 */
public interface EmailVerificationStore {

    /** 같은 이메일로 다시 보낼 수 있는지 확인하고, 되면 재발송 간격을 시작한다. 간격 안이면 false */
    boolean tryStartResendCooldown(String email);

    /** 재발송 간격을 지운다 (메일을 못 보냈을 때 바로 다시 받을 수 있게) */
    void clearResendCooldown(String email);

    /** 이 IP의 발송을 하나 센다. 한도를 넘었으면 false (센 수는 그대로 남아 기간 동안 계속 막힌다) */
    boolean tryCountIp(String ip);

    /** 이 이메일로 보내는 메일을 하나 센다(하루). 한도를 넘었으면 false */
    boolean tryCountEmailDaily(String email);

    /** 앱 전체 발송을 하나 센다(하루). 한도를 넘었으면 false */
    boolean tryCountGlobalDaily();

    /** 인증 코드를 저장한다. 같은 이메일·용도의 이전 코드와 틀린 횟수는 지운다 */
    void saveCode(String email, EmailVerificationPurpose purpose, String code);

    /** 저장한 코드를 지운다 */
    void deleteCode(String email, EmailVerificationPurpose purpose);

    /**
     * 코드를 확인한다. 맞으면 코드를 지운다. 틀리면 틀린 횟수를 세고, {@code maxAttempts}번째로 틀리면 코드를 지운다
     */
    CodeCheck checkCode(String email, EmailVerificationPurpose purpose, String code, int maxAttempts);

    /** 인증을 마친 이메일에 그 용도의 인증 토큰을 발급한다. 토큰을 가진 사람만 그 이메일로 그 일(가입·재설정)을 할 수 있다 */
    String issueToken(String email, EmailVerificationPurpose purpose);

    /** 그 용도의 인증 토큰에 묶인 이메일. 없거나 만료됐거나 다른 용도의 토큰이면 빈 값 */
    Optional<String> findTokenEmail(String token, EmailVerificationPurpose purpose);

    /** 인증 토큰을 꺼내면서 바로 지운다(한 번에). 같은 토큰으로 동시에 와도 한 요청만 이메일을 받는다. 없으면 빈 값 */
    Optional<String> takeTokenEmail(String token, EmailVerificationPurpose purpose);

    /** 인증 토큰을 지운다 (쓰면 지워 한 번만 쓰게 한다) */
    void deleteToken(String token, EmailVerificationPurpose purpose);

    /** 코드 확인 결과 */
    enum CodeCheck {
        /** 맞음 (코드는 지워짐) */
        MATCHED,
        /** 틀림 (아직 더 시도할 수 있음) */
        WRONG,
        /** 정한 횟수만큼 틀려 코드를 지움 */
        ATTEMPTS_EXCEEDED,
        /** 코드가 없음 (받지 않았거나 10분이 지났거나 이미 썼음) */
        NOT_FOUND
    }
}
