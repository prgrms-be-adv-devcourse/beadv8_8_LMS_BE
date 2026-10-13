package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

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

    public void replaceContents(Long sellerUserId, Long lectureId, List<LectureContentSpec> contentSpecs) {
        lectureSupport.findLecture(lectureId).replaceContents(sellerUserId, contentSpecs, LocalDateTime.now());
        lectureFileSupport.validateAttachable(sellerUserId, null, contentSpecs);
    }
}
