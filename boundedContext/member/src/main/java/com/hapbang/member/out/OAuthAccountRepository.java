package com.hapbang.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;

/** OAuth 계정 저장소 */
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    /**
     * 제공자와 제공자 ID로 연결된 계정 조회 (로그인 시 회원 찾기).
     * 회원을 함께 조회한다. 재로그인 시 Facade 트랜잭션 밖에서 회원 정보를 읽으면 LazyInitializationException이 발생
     * (MemberOAuthLoginIntegrationTest.secondLogin에서 확인)
     */
    @EntityGraph(attributePaths = "user")
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
