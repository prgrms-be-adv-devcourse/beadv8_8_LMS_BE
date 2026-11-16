package com.hapbang.member.out;

import org.jspecify.annotations.Nullable;

import com.hapbang.member.domain.ClientIpKey;

/**
 * 비밀번호 로그인 실패 횟수·잠금 (#499, #506). 이메일 잠금이 남의 로그인을 막는 데 쓰이지 않게 (이메일, IP)마다 센다.
 * <ul>
 *     <li>(이메일, IP): 연속으로 정해진 횟수(기본 5번)를 틀리면 그 IP에서 그 이메일을 잠근다(기본 15분). 다른 IP는 영향이 없다</li>
 *     <li>이메일 전체: IP와 상관없이 기간(기본 1시간) 안에 정해진 횟수(기본 20번)를 틀리면 그 이메일을 잠근다(기본 15분). IP를 여러 개 쓰는 대입을
 *     막는다. 최근(기본 30일) 그 이메일로 로그인에 성공한 IP는 이 잠금을 받지 않는다</li>
 *     <li>IP를 모르면({@code ipKey}가 null, 공인 주소가 아님) 모르는 IP 하나로 보고 센다(#499의 이메일 잠금과 같아진다).
 *     모르는 IP는 성공해도 기억하지 않는다</li>
 * </ul>
 * 이메일은 정규화한 값({@code MemberEmail.normalize})을 넘긴다.
 */
public interface PasswordLoginLockStore {

    /** 이 이메일을 이 IP에서 지금 받을 수 없으면 잠금 종류, 받을 수 있으면 {@link Lock#NONE} */
    Lock check(String email, @Nullable ClientIpKey ipKey);

    /** 실패를 하나 센다. 이번에 잠긴 것을 돌려준다 */
    FailureResult recordFailure(String email, @Nullable ClientIpKey ipKey);

    /** 비밀번호가 맞으면 이 IP의 실패 횟수를 지우고, 공인 IP면 성공한 IP로 기억한다 (이메일 전체 실패 수는 지우지 않는다) */
    void recordSuccess(String email, @Nullable ClientIpKey ipKey);

    /** 이 이메일의 실패 횟수와 잠금을 IP와 상관없이 모두 지운다 (비밀번호 재설정, #557). 성공한 IP 기억은 남긴다 */
    void unlockAll(String email);

    enum Lock {
        NONE,
        /** (이메일, IP) 잠금 */
        EMAIL_IP,
        /** 이메일 전체 잠금 (이 IP가 성공한 IP가 아님) */
        EMAIL
    }

    /**
     * @param emailIpLocked 이번 실패로 (이메일, IP)가 잠겼다
     * @param emailLocked   이번 실패로 이메일 전체가 잠겼다
     */
    record FailureResult(boolean emailIpLocked, boolean emailLocked) {
    }
}
