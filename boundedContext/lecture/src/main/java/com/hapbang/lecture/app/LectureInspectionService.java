package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 강의 검수 (설계 8.2).
 */
// 관리자 여부는 컨트롤러(/admin 경로, @PreAuthorize)에서 검사한다. 서비스로 분리할 때는 서비스단에서 검증한다 (2026-10-12 회의).
@Service
@RequiredArgsConstructor
public class LectureInspectionService {

    private static final Sort OLDEST_REQUEST = Sort.by(Sort.Order.asc("inspectionRequestedAt"), Sort.Order.asc("id"));

    private final LectureRepository lectureRepository;
    private final SellerNicknames sellerNicknames;

    /** 삭제된 강의는 빠진다. 신청이 오래된 순이며 요청의 정렬은 무시한다. */
    @Transactional(readOnly = true)
    public Page<InspectionLectureResponse> getInspections(LectureStatus status, Pageable pageable) {
        Page<Lecture> lectures = lectureRepository.findByStatusAndDeletedAtIsNull(status,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), OLDEST_REQUEST));
        Map<Long, String> nicknames = sellerNicknames.of(lectures.map(Lecture::getSellerUserId).toSet());
        return lectures.map(lecture -> InspectionLectureResponse.of(lecture, nicknames.get(lecture.getSellerUserId())));
    }

    @Transactional
    public void approve(Long adminUserId, Long lectureId) {
        findLecture(lectureId).approve(adminUserId, LocalDateTime.now());
    }

    @Transactional
    public void reject(Long adminUserId, Long lectureId, String reason) {
        findLecture(lectureId).reject(adminUserId, reason, LocalDateTime.now());
    }

    private Lecture findLecture(Long lectureId) {
        return lectureRepository.findById(lectureId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.LECTURE_NOT_FOUND));
    }
}
