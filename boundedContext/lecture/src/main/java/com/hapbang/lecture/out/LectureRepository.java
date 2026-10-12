package com.hapbang.lecture.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureStatus;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    Page<Lecture> findByStatusAndDeletedAtIsNull(LectureStatus status, Pageable pageable);

    Page<Lecture> findByStatusAndDeletedAtIsNullAndTitleContainingIgnoreCase(LectureStatus status, String keyword,
                                                                            Pageable pageable);

    Page<Lecture> findBySellerUserId(Long sellerUserId, Pageable pageable);
}
