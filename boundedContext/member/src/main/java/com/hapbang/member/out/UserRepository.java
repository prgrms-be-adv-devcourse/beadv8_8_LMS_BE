package com.hapbang.member.out;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원 저장소 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /** 역할·상태로 회원 목록 (인플루언서 목록) */
    Page<User> findByRoleInAndStatus(Collection<Role> roles, UserStatus status, Pageable pageable);
}
