package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/** 강의 등록. 검수 대기로 시작한다. 강의 값을 먼저 검사하고 파일은 나중에 검사한다 (D21) */
@Service
@RequiredArgsConstructor
public class LectureRegisterUseCase {

    private final LectureRepository lectureRepository;
    private final LectureFileSupport lectureFileSupport;

    public Long register(Long sellerUserId, String title, String description, long priceHp, Long thumbnailFileId,
                         List<LectureContentSpec> contentSpecs) {
        Lecture lecture = Lecture.register(sellerUserId, title, description, priceHp, thumbnailFileId, contentSpecs,
                LocalDateTime.now());
        lectureFileSupport.validateAttachable(sellerUserId, thumbnailFileId, contentSpecs);
        return lectureRepository.save(lecture).getId();
    }
}
