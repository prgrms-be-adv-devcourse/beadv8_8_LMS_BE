package com.hapbang.lecture.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.ContentAccessResponse;
import com.hapbang.lecture.app.dto.LectureDetailResponse;
import com.hapbang.lecture.app.dto.LectureSort;
import com.hapbang.lecture.app.dto.LectureSummaryResponse;
import com.hapbang.lecture.app.dto.MyLectureResponse;
import com.hapbang.lecture.domain.LectureContentSpec;

import lombok.RequiredArgsConstructor;

/**
 * 강의 등록·수정·삭제·조회 진입점 (설계 8.1).
 * 판매자 기능의 역할 검사(인증된 인플루언서)는 여기서 한다 (D16). 판매자 본인 여부는 Lecture가 검사한다.
 * 로그인 정보가 없는 호출(배치·이벤트 리스너)은 판매자 기능에서 거부된다.
 */
@Service
@RequiredArgsConstructor
public class LectureFacade {

    private static final String VERIFIED_INFLUENCER = "hasRole('VERIFIED_INFLUENCER')";

    private final LectureRegisterUseCase lectureRegisterUseCase;
    private final LectureUpdateInfoUseCase lectureUpdateInfoUseCase;
    private final LectureReplaceContentsUseCase lectureReplaceContentsUseCase;
    private final LectureRequestInspectionUseCase lectureRequestInspectionUseCase;
    private final LectureDeleteUseCase lectureDeleteUseCase;
    private final LectureSupport lectureSupport;
    private final LectureContentAccessSupport lectureContentAccessSupport;

    /** 강의 등록 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional
    public Long register(Long sellerUserId, String title, String description, long priceHp, Long thumbnailFileId,
                         List<LectureContentSpec> contentSpecs) {
        return lectureRegisterUseCase.register(sellerUserId, title, description, priceHp, thumbnailFileId,
                contentSpecs);
    }

    /** 강의 정보 수정 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional
    public void updateInfo(Long sellerUserId, Long lectureId, String title, String description, long priceHp,
                           Long thumbnailFileId) {
        lectureUpdateInfoUseCase.updateInfo(sellerUserId, lectureId, title, description, priceHp, thumbnailFileId);
    }

    /** 콘텐츠 목록 교체 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional
    public void replaceContents(Long sellerUserId, Long lectureId, List<LectureContentSpec> contentSpecs) {
        lectureReplaceContentsUseCase.replaceContents(sellerUserId, lectureId, contentSpecs);
    }

    /** 재검수 신청 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional
    public void requestInspection(Long sellerUserId, Long lectureId) {
        lectureRequestInspectionUseCase.requestInspection(sellerUserId, lectureId);
    }

    /** 강의 삭제 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional
    public void delete(Long sellerUserId, Long lectureId) {
        lectureDeleteUseCase.delete(sellerUserId, lectureId);
    }

    /** 판매 중인 강의 목록 (비회원 포함) */
    @Transactional(readOnly = true)
    public Page<LectureSummaryResponse> getLectures(String keyword, LectureSort sort, Pageable pageable) {
        return lectureSupport.getLectures(keyword, sort, pageable);
    }

    /** 내가 등록한 강의 */
    @PreAuthorize(VERIFIED_INFLUENCER)
    @Transactional(readOnly = true)
    public Page<MyLectureResponse> getMyLectures(Long sellerUserId, Pageable pageable) {
        return lectureSupport.getMyLectures(sellerUserId, pageable);
    }

    /** 강의 상세. 관리자의 검수용 조회는 {@link LectureInspectionFacade#getLecture}다 (D30) */
    @Transactional(readOnly = true)
    public LectureDetailResponse getLecture(Long userId, Long lectureId) {
        return lectureSupport.getLecture(userId, lectureId);
    }

    /**
     * 콘텐츠 시청 URL 발급.
     * 판매자 본인 또는 관리자 또는 구매자라는 OR 조건이라 역할 검사(@PreAuthorize)를 두지 않고 Support가 판정한다 (D26, D38).
     */
    @Transactional(readOnly = true)
    public ContentAccessResponse getContentAccess(Long userId, Long lectureId, Long contentId) {
        return lectureContentAccessSupport.issue(userId, lectureId, contentId);
    }
}
