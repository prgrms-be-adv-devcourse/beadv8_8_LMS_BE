package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Lecture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 강의 삭제 (Soft Delete). 판매자 본인 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureDeleteUseCase {

    private final LectureSupport lectureSupport;

    public void delete(Long sellerUserId, Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        boolean alreadyDeleted = lecture.isDeleted();
        lecture.delete(sellerUserId, LocalDateTime.now());
        // 이미 삭제된 강의의 삭제는 아무것도 바꾸지 않으므로 남기지 않는다
        if (!alreadyDeleted) {
            log.info("강의를 삭제했다 lectureId={}, sellerUserId={}", lectureId, sellerUserId);
        }
    }
}
