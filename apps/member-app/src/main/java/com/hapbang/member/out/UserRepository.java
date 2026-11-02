package com.hapbang.member.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원 저장소 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 가입할 때 이메일 중복 확인 (#499). 구글 가입 이메일은 받은 그대로 저장돼 대소문자가 섞일 수 있고
     * users.email 유일 제약은 대소문자를 구별하므로, 대소문자를 무시하고 찾는다
     */
    boolean existsByEmailIgnoreCase(String email);

    /** 구글 가입 때 같은 이메일 회원 확인 (#499). 대소문자를 무시한다 (existsByEmailIgnoreCase와 같은 이유) */
    Optional<User> findByEmailIgnoreCase(String email);

    /** 역할·상태로 회원 목록 (인플루언서 목록) */
    Page<User> findByRoleInAndStatus(Collection<Role> roles, UserStatus status, Pageable pageable);

    /** 내부 회원 목록(키셋): afterId보다 큰 ID를 오름차순으로 limit개. 모든 역할·상태 포함 (#303) */
    List<User> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);
}
