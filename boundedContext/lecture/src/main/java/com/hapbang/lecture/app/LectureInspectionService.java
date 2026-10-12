package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.app.dto.PageResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 강의 검수 (설계 8.2).
 */
// TODO(security): 관리자만 호출하도록 인가(설계 9장)를 추가한다.
@Service
@RequiredArgsConstructor
public class LectureInspectionService {

    private static final Sort OLDEST_REQUEST = Sort.by(Sort.Order.asc("inspectionRequestedAt"), Sort.Order.asc("id"));

    private final LectureRepository lectureRepository;
    private final SellerNicknames sellerNicknames;

    /** 삭제된 강의는 빠진다 */
    @Transactional(readOnly = true)
    public PageResponse<InspectionLectureResponse> getInspections(LectureStatus status, int page, int size) {
        Page<Lecture> lectures = lectureRepository.findByStatusAndDeletedAtIsNull(status,
                PageRequest.of(page, size, OLDEST_REQUEST));
        Map<Long, String> nicknames = sellerNicknames.of(lectures.map(Lecture::getSellerUserId).toSet());
        return PageResponse.of(lectures,
                lecture -> InspectionLectureResponse.of(lecture, nicknames.get(lecture.getSellerUserId())));
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
