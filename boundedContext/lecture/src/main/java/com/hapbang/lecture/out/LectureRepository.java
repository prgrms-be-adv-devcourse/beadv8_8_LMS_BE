package com.hapbang.lecture.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.Lecture;

public interface LectureRepository extends JpaRepository<Lecture, Long> {
}
