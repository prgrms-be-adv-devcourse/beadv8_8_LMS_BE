package com.hapbang.lecture.app;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import lombok.RequiredArgsConstructor;

/**
 * 강의 조회 내부 API 진입점. 쿠폰이 판매자 소유 확인·가격 snapshot(하나)과 쿠폰 받기 화면의 강의 제목·판매 중 거르기(일괄)에 쓴다.
 * 읽기만 하고 아무것도 바꾸지 않는다. 삭제된 강의는 팔 수도 쿠폰을 걸 수도 없으므로 없는 강의로 답한다(하나는 LECTURE_NOT_FOUND,
 * 일괄은 값 {@code null}).
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class LectureLookupInternalFacade {

    private final LectureSupport lectureSupport;
    private final LectureRepository lectureRepository;

    @Transactional(readOnly = true)
    public LectureLookupDto getLecture(Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        if (lecture.isDeleted()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return toDto(lecture);
    }

    /**
     * 강의 여러 개. 요청한 ID마다 키가 있고(중복은 한 번, 요청 순서), 없거나 삭제된 강의는 값이 {@code null}이다.
     * 판매 상태로 거르지 않는다(하나 조회와 같다). 개수 검사는 컨트롤러가 한다
     */
    @Transactional(readOnly = true)
    public Map<Long, LectureLookupDto> getLectures(Collection<Long> lectureIds) {
        Set<Long> ids = new LinkedHashSet<>(lectureIds);
        Map<Long, LectureLookupDto> found = new HashMap<>();
        for (Lecture lecture : lectureRepository.findAllById(ids)) {
            if (!lecture.isDeleted()) {
                found.put(lecture.getId(), toDto(lecture));
            }
        }
        Map<Long, LectureLookupDto> lectures = new LinkedHashMap<>();
        for (Long lectureId : ids) {
            lectures.put(lectureId, found.get(lectureId));
        }
        return lectures;
    }

    private static LectureLookupDto toDto(Lecture lecture) {
        return new LectureLookupDto(lecture.getId(), lecture.getSellerUserId(), lecture.getPriceHp(),
                lecture.getStatus().name(), lecture.getTitle());
    }
}
