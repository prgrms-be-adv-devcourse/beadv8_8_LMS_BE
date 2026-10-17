package com.hapbang.lecture.app;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.InspectionLectureResponse;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureRating;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureStatus;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/** 강의 조회 공통 (없으면 LECTURE_NOT_FOUND). 응답 DTO 변환은 호출한 Facade의 트랜잭션 안에서 끝난다. */
@Service
@RequiredArgsConstructor
public class LectureSupport {

    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Sort OLDEST_REQUEST = Sort.by(Sort.Order.asc("inspectionRequestedAt"), Sort.Order.asc("id"));

    private final LectureRepository lectureRepository;
    private final LectureUserNicknameSupport nicknameSupport;
    private final LectureThumbnailSupport lectureThumbnailSupport;
    private final LectureOrderSupport lectureOrderSupport;
    private final LectureRatingSupport lectureRatingSupport;

    public Lecture findLecture(Long lectureId) {
        return lectureRepository.findById(lectureId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.LECTURE_NOT_FOUND));
    }

    /**
     * 판매 중인 강의 목록. 검색어가 있으면 제목에 검색어가 들어간 강의만 찾는다(대소문자 무시).
     * 정렬은 {@link LectureSort}로만 정하고 요청의 필드 이름 정렬은 무시한다.
     */
    public Page<LectureSummaryResponse> getLectures(String keyword, LectureSort sort, Pageable pageable) {
        Page<Lecture> lectures = findOnSale(keyword == null ? "" : keyword.strip(), sort, pageable);
        Map<Long, String> nicknames = nicknameSupport.of(lectures.map(Lecture::getSellerUserId).toSet());
        Map<Long, String> thumbnailUrls = lectureThumbnailSupport.urlsOf(
                lectures.map(Lecture::getThumbnailFileId).toSet());
        Map<Long, LectureRating> ratings = lectureRatingSupport.of(lectures.map(Lecture::getId).toSet());
        return lectures.map(lecture -> LectureSummaryResponse.of(lecture, nicknames.get(lecture.getSellerUserId()),
                thumbnailUrls.get(lecture.getThumbnailFileId()), ratings.get(lecture.getId())));
    }

    /** 평점순은 평균 평점을 집계해 정렬하므로 쿼리를 따로 쓴다 */
    private Page<Lecture> findOnSale(String keyword, LectureSort sort, Pageable pageable) {
        if (sort == LectureSort.RATING) {
            return lectureRepository.findOnSaleOrderByRating(keyword,
                    PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        }
        PageRequest sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortOf(sort));
        return keyword.isEmpty()
                ? lectureRepository.findByStatusAndDeletedAtIsNull(LectureStatus.ON_SALE, sorted)
                : lectureRepository.findByStatusAndDeletedAtIsNullAndTitleContainingIgnoreCase(LectureStatus.ON_SALE,
                        keyword, sorted);
    }

    /** 판매자 본인이 등록한 강의 (모든 상태, 삭제 포함). 최근 등록순이며 요청의 정렬은 무시한다. */
    public Page<MyLectureResponse> getMyLectures(Long sellerUserId, Pageable pageable) {
        Page<Lecture> lectures = lectureRepository.findBySellerUserId(sellerUserId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST));
        Map<Long, String> thumbnailUrls = lectureThumbnailSupport.urlsOf(
                lectures.map(Lecture::getThumbnailFileId).toSet());
        return lectures.map(lecture -> MyLectureResponse.of(lecture, thumbnailUrls.get(lecture.getThumbnailFileId())));
    }

    /**
     * 회원이 볼 수 있는 강의. 판매 중이 아니거나 삭제된 강의는 판매자 본인과 이 강의를 보유한 구매자만 조회한다(D5, D38).
     * 다른 회원에게는 없는 강의로 보인다. 보유 항목은 판매 중이 아닐 때만 읽는다.
     */
    public Lecture findViewableLecture(Long userId, Long lectureId) {
        Lecture lecture = findLecture(lectureId);
        if (!lecture.getSellerUserId().equals(userId) && !lecture.isOnSale()
                && lectureOrderSupport.findHeldItem(userId, lectureId).isEmpty()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return lecture;
    }

    /** 볼 수 있는 강의는 {@link #findViewableLecture}와 같다. 반려 사유와 파일 ID는 판매자 본인에게만 보여 준다 (D53) */
    public LectureDetailResponse getLecture(Long userId, Long lectureId) {
        Lecture lecture = findViewableLecture(userId, lectureId);
        boolean seller = lecture.getSellerUserId().equals(userId);
        return LectureDetailResponse.of(lecture, nicknameSupport.of(lecture.getSellerUserId()),
                lectureThumbnailSupport.urlOf(lecture.getThumbnailFileId()), lectureRatingSupport.of(lectureId),
                seller);
    }

    /** 관리자 검수용 강의 상세. 상태·삭제 여부와 관계없이 조회하고 반려 사유와 파일 ID도 보여 준다 (D30, D53). */
    public LectureDetailResponse getLectureForInspection(Long lectureId) {
        Lecture lecture = findLecture(lectureId);
        return LectureDetailResponse.of(lecture, nicknameSupport.of(lecture.getSellerUserId()),
                lectureThumbnailSupport.urlOf(lecture.getThumbnailFileId()), lectureRatingSupport.of(lectureId), true);
    }

    /** 검수 목록. 삭제된 강의는 빠진다. 신청이 오래된 순이며 요청의 정렬은 무시한다. */
    public Page<InspectionLectureResponse> getInspections(LectureStatus status, Pageable pageable) {
        Page<Lecture> lectures = lectureRepository.findByStatusAndDeletedAtIsNull(status,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), OLDEST_REQUEST));
        Map<Long, String> nicknames = nicknameSupport.of(lectures.map(Lecture::getSellerUserId).toSet());
        return lectures.map(lecture -> InspectionLectureResponse.of(lecture, nicknames.get(lecture.getSellerUserId())));
    }

    /** 같은 가격이면 최신순. 평점순은 {@link LectureRepository#findOnSaleOrderByRating}이 정렬한다 */
    private static Sort sortOf(LectureSort sort) {
        return switch (sort) {
            case LATEST, RATING -> LATEST;
            case PRICE_ASC -> Sort.by(Sort.Order.asc("priceHp")).and(LATEST);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("priceHp")).and(LATEST);
        };
    }
}
