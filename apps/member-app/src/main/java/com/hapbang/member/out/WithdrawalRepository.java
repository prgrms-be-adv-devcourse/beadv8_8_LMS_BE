package com.hapbang.member.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.Withdrawal;

/** 회원 탈퇴 작업 기록 저장소 (#593) */
public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {
}
