package com.hapbang.member.out;

/**
 * 가입 이메일 인증 저장소 (#557). 인증 코드, 같은 이메일 재발송 간격, IP당 발송 횟수를 둔다.
 * 이메일·IP는 원문 대신 해시로 키를 만든다
 */
public interface EmailVerificationStore {

    /** 같은 이메일로 다시 보낼 수 있는지 확인하고, 되면 재발송 간격을 시작한다. 간격 안이면 false */
    boolean tryStartResendCooldown(String email);

    /** 재발송 간격을 지운다 (메일을 못 보냈을 때 바로 다시 받을 수 있게) */
    void clearResendCooldown(String email);

    /** 이 IP의 발송을 하나 센다. 한도를 넘었으면 false (센 수는 그대로 남아 한 시간 동안 계속 막힌다) */
    boolean tryCountIp(String ip);

    /** 인증 코드를 저장한다. 같은 이메일의 이전 코드와 틀린 횟수는 지운다 */
    void saveCode(String email, String code);

    /** 저장한 코드를 지운다 */
    void deleteCode(String email);
}
