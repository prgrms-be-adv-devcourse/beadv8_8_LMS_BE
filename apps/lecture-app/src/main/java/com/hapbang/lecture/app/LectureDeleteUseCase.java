package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 강의 삭제 (Soft Delete). 판매자 본인 */
@Service
@RequiredArgsConstructor
public class LectureDeleteUseCase {

    private final LectureSupport lectureSupport;

    public void delete(Long sellerUserId, Long lectureId) {
        lectureSupport.findLecture(lectureId).delete(sellerUserId, LocalDateTime.now());
    }
}
