package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.LectureContentSpec;

import lombok.RequiredArgsConstructor;

/** 콘텐츠 목록 교체. 판매자 본인, 반려된 강의만 */
@Service
@RequiredArgsConstructor
public class LectureReplaceContentsUseCase {

    private final LectureSupport lectureSupport;

    public void replaceContents(Long sellerUserId, Long lectureId, List<LectureContentSpec> contentSpecs) {
        lectureSupport.findLecture(lectureId).replaceContents(sellerUserId, contentSpecs, LocalDateTime.now());
    }
}
