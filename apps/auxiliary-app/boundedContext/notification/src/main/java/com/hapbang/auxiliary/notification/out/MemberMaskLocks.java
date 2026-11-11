package com.hapbang.auxiliary.notification.out;

import java.util.Collection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 회원 단위 파기 순서 잠금(ND-054). PostgreSQL advisory lock(트랜잭션 범위)이라 회원 복제본 행이 없어도 잠긴다.
 * <ul>
 *     <li>알림 저장은 언급 회원마다 공유 잠금을 ID 오름차순으로 잡는다(저장끼리는 막지 않는다).</li>
 *     <li>파기 처리는 그 회원에 배타 잠금을 잡는다. 그 회원을 언급한 저장이 끝날 때까지 기다리고, 그 뒤 저장은 파기 표시를 본다.</li>
 * </ul>
 * 키는 두 정수 형식 ({@value #NAMESPACE}, 회원 ID 해시)이다. global {@code JobLock}의 한 정수(bigint) 번호와 키 공간이 겹치지 않는다.
 * 해시가 겹치면 다른 회원끼리 잠깐 기다릴 뿐 결과는 같다. 트랜잭션이 끝나면 풀린다.
 */
@Component
@RequiredArgsConstructor
public class MemberMaskLocks {

    /** 이 잠금을 더한 이슈 번호(#801) */
    static final int NAMESPACE = 801;

    private final JdbcTemplate jdbcTemplate;

    /** 언급 회원마다 공유 잠금. 호출한 쪽의 트랜잭션 안에서만 부른다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockShared(Collection<Long> userIds) {
        userIds.stream().distinct().sorted().forEach(userId -> jdbcTemplate.query(
                "select pg_advisory_xact_lock_shared(?, ?)", rs -> { }, NAMESPACE, Long.hashCode(userId)));
    }

    /** 파기할 회원에 배타 잠금. 호출한 쪽의 트랜잭션 안에서만 부른다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockExclusive(long userId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, ?)", rs -> { }, NAMESPACE, Long.hashCode(userId));
    }
}
