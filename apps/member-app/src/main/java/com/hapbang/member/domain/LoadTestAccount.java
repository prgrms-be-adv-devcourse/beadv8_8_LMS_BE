package com.hapbang.member.domain;

import java.util.regex.Pattern;

/**
 * 채팅 부하 시험 계정 (2026-11-04 사용자 결정, docs/policy.md "부하 시험 회원 승급 예외"). 부하 시험 스크립트
 * (apps/chat-app/docs/experiments/scripts/accounts.py)가 만드는 이메일 형식(배치당 001~100)으로만 구분한다.
 * <ul>
 *     <li>채널 인증 없이 인플루언서로 바꿀 수 있다(MemberTestAccountFacade).</li>
 *     <li>이메일 인증 없이 가입할 수 있다(#557 임시 예외). {@code .invalid}는 실제로 있을 수 없는 주소라 남의 이메일을 사칭하는 데
 *     쓸 수 없다. 나중에 따로 막는 방식으로 바꾼다.</li>
 * </ul>
 */
public final class LoadTestAccount {

    private static final Pattern EMAIL = Pattern.compile(
            "k6-[0-9]{8}-[0-9]{6}-[0-9a-f]{6}-(?:00[1-9]|0[1-9][0-9]|100)@example\\.invalid");

    private LoadTestAccount() {
    }

    /** 부하 시험 계정 이메일인지 (정규화한 이메일을 넘긴다) */
    public static boolean isLoadTestEmail(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }
}
