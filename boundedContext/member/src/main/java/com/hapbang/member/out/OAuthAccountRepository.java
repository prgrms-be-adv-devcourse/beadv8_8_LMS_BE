package com.hapbang.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;

/** OAuth 계정 저장소 */
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    /** 제공자와 제공자 ID로 연결된 계정 조회 (로그인 시 회원 찾기) */
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
