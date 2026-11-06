package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import lombok.RequiredArgsConstructor;

/**
 * 강의 조회 내부 API 진입점. 쿠폰이 판매자 소유 확인과 가격 snapshot에 쓴다. 읽기만 하고 아무것도 바꾸지 않는다.
 * 삭제된 강의는 팔 수도 쿠폰을 걸 수도 없으므로 없는 강의(LECTURE_NOT_FOUND)로 답한다.
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class LectureLookupInternalFacade {

    private final LectureSupport lectureSupport;

    @Transactional(readOnly = true)
    public LectureLookupDto getLecture(Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        if (lecture.isDeleted()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return new LectureLookupDto(lecture.getId(), lecture.getSellerUserId(), lecture.getPriceHp(),
                lecture.getStatus().name());
    }
}
