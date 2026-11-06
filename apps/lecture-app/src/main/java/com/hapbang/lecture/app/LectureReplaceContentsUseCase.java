package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;

import lombok.RequiredArgsConstructor;

/**
 * 콘텐츠 목록 교체. 판매자 본인, 반려된 강의만.
 * 강의를 먼저 검사하고 파일은 나중에 검사한다. 파일이 맞지 않으면 Facade 트랜잭션이 롤백되어 교체도 취소된다 (D21)
 */
@Service
@RequiredArgsConstructor
public class LectureReplaceContentsUseCase {

    private final LectureSupport lectureSupport;
    private final LectureFileSupport lectureFileSupport;
    private final LectureSyncEvents lectureSyncEvents;

    public void replaceContents(Long sellerUserId, Long lectureId, List<LectureContentSpec> contentSpecs) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.replaceContents(sellerUserId, contentSpecs, LocalDateTime.now());
        lectureFileSupport.validateAttachable(sellerUserId, null, contentSpecs);
        // 콘텐츠는 강의 버전이 없지만, 교체가 강의의 수정 시각을 바꿔 강의 버전이 오른다
        lectureSyncEvents.lectureChanged(lecture);
    }
}
