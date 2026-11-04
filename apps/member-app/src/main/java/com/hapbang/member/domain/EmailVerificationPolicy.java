package com.hapbang.member.domain;

import java.time.Duration;

/**
 * 가입 이메일 인증 규칙 (#557, docs/policy.md "이메일 인증"). 시간·횟수를 한곳에 둔다.
 * 저장소(Redis)·UseCase·API 응답이 모두 이 값을 읽는다
 */
public final class EmailVerificationPolicy {

    /** 인증 코드 유효 시간 */
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 코드 하나를 틀릴 수 있는 횟수. 이만큼 틀리면 코드를 지워 다시 받아야 한다 */
    public static final int MAX_CODE_ATTEMPTS = 5;
    /** 인증 토큰 유효 시간 (그 이메일로 한 번 가입할 수 있다) */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /** 같은 이메일로 다시 받을 수 있는 간격 */
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    /** 한 IP가 받을 수 있는 메일 수와 세는 기간 (#557 결정: 1시간 10통) */
    public static final int IP_LIMIT = 10;
    public static final Duration IP_WINDOW = Duration.ofHours(1);
    /** 한 이메일이 받을 수 있는 메일 수와 세는 기간 (#557 결정: 하루 3통, 메일 폭탄·코드 맞히기 방지) */
    public static final int EMAIL_DAILY_LIMIT = 3;
    public static final Duration EMAIL_WINDOW = Duration.ofDays(1);
    /** 앱 전체가 보낼 수 있는 메일 수와 세는 기간 (#557 결정: 하루 300통, Gmail 하루 한도 약 500통보다 낮게) */
    public static final int GLOBAL_DAILY_LIMIT = 300;
    public static final Duration GLOBAL_WINDOW = Duration.ofDays(1);

    private EmailVerificationPolicy() {
    }
}
