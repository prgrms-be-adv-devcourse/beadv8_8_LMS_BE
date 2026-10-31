package com.hapbang.lecture.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureUser;

public interface LectureUserRepository extends JpaRepository<LectureUser, Long> {
}
