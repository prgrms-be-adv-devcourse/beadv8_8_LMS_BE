package com.hapbang.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.PasswordCredential;

/** 비밀번호 로그인 정보 저장소 (#499) */
public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, Long> {

    /** 로그인: 이메일로 비밀번호 정보와 회원을 함께 조회한다 (회원 상태·역할을 트랜잭션 밖에서도 읽는다) */
    @EntityGraph(attributePaths = "user")
    Optional<PasswordCredential> findByUserEmail(String email);

    /** 비밀번호 변경: 로그인한 회원의 비밀번호 정보 */
    Optional<PasswordCredential> findByUserId(Long userId);

    /** 같은 이메일의 구글 로그인을 막을 때: 이 회원이 비밀번호로 가입했는지 */
    boolean existsByUserId(Long userId);
}
