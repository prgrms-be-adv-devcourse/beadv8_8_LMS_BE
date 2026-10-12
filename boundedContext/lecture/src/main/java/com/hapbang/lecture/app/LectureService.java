package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.app.dto.PageResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/**
 * 강의 등록·수정·삭제·조회 (설계 8.1).
 */
// TODO(security): 역할별 인가(설계 9장)가 들어오면 관리자도 검수 대기·반려·삭제된 강의 상세를 조회하게 한다.
@Service
@RequiredArgsConstructor
public class LectureService {

    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final LectureRepository lectureRepository;
    private final SellerNicknames sellerNicknames;

    @Transactional
    public Long register(Long sellerUserId, String title, String description, long priceHp, Long thumbnailFileId,
                         List<LectureContentSpec> contentSpecs) {
        Lecture lecture = Lecture.register(sellerUserId, title, description, priceHp, thumbnailFileId, contentSpecs,
                LocalDateTime.now());
        return lectureRepository.save(lecture).getId();
    }

    @Transactional
    public void updateInfo(Long sellerUserId, Long lectureId, String title, String description, long priceHp,
                           Long thumbnailFileId) {
        findLecture(lectureId).updateInfo(sellerUserId, title, description, priceHp, thumbnailFileId,
                LocalDateTime.now());
    }

    @Transactional
    public void replaceContents(Long sellerUserId, Long lectureId, List<LectureContentSpec> contentSpecs) {
        findLecture(lectureId).replaceContents(sellerUserId, contentSpecs, LocalDateTime.now());
    }

    @Transactional
    public void requestInspection(Long sellerUserId, Long lectureId) {
        findLecture(lectureId).requestInspection(sellerUserId, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long sellerUserId, Long lectureId) {
        findLecture(lectureId).delete(sellerUserId, LocalDateTime.now());
    }

    /** 판매 중인 강의 목록. 검색어가 있으면 제목에 검색어가 들어간 강의만 찾는다(대소문자 무시). */
    @Transactional(readOnly = true)
    public PageResponse<LectureSummaryResponse> getLectures(String keyword, LectureSort sort, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, sortOf(sort));
        Page<Lecture> lectures = keyword == null || keyword.isBlank()
                ? lectureRepository.findByStatusAndDeletedAtIsNull(LectureStatus.ON_SALE, pageable)
                : lectureRepository.findByStatusAndDeletedAtIsNullAndTitleContainingIgnoreCase(LectureStatus.ON_SALE,
                        keyword.strip(), pageable);
        Map<Long, String> nicknames = sellerNicknames.of(lectures.map(Lecture::getSellerUserId).toSet());
        return PageResponse.of(lectures,
                lecture -> LectureSummaryResponse.of(lecture, nicknames.get(lecture.getSellerUserId())));
    }

    /** 판매자 본인이 등록한 강의 (모든 상태, 삭제 포함) */
    @Transactional(readOnly = true)
    public PageResponse<MyLectureResponse> getMyLectures(Long sellerUserId, int page, int size) {
        return PageResponse.of(lectureRepository.findBySellerUserId(sellerUserId, PageRequest.of(page, size, LATEST)),
                MyLectureResponse::from);
    }

    /**
     * 판매 중이 아니거나 삭제된 강의는 판매자 본인만 조회한다. 다른 회원에게는 없는 강의로 보인다.
     */
    @Transactional(readOnly = true)
    public LectureDetailResponse getLecture(Long userId, Long lectureId) {
        Lecture lecture = findLecture(lectureId);
        boolean seller = lecture.getSellerUserId().equals(userId);
        if (!seller && !lecture.isOnSale()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return LectureDetailResponse.of(lecture, sellerNicknames.of(lecture.getSellerUserId()), seller);
    }

    /** 같은 가격이면 최신순 */
    // TODO: 평점순(RATING)은 6단계(리뷰)에서 구현한다. 그 전까지는 최신순으로 보여 준다.
    private static Sort sortOf(LectureSort sort) {
        return switch (sort) {
            case LATEST, RATING -> LATEST;
            case PRICE_ASC -> Sort.by(Sort.Order.asc("priceHp")).and(LATEST);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("priceHp")).and(LATEST);
        };
    }

    private Lecture findLecture(Long lectureId) {
        return lectureRepository.findById(lectureId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.LECTURE_NOT_FOUND));
    }
}
