package com.hapbang.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.User;

/** 회원 저장소 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
}
