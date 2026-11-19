package com.hapbang.lecture.in;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureLookupInternalFacade;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import lombok.RequiredArgsConstructor;

/** 강의 조회 내부 API. Swagger 명세는 {@link ApiV1LectureLookupInternalSpec} */
@RestController
@RequestMapping("/api/v1/internal/lectures")
@RequiredArgsConstructor
public class ApiV1LectureLookupInternalController implements ApiV1LectureLookupInternalSpec {

    private final LectureLookupInternalFacade lectureLookupInternalFacade;

    @Override
    @GetMapping("/{lectureId}")
    public RsData<LectureLookupDto> getLecture(@PathVariable("lectureId") Long lectureId) {
        return RsData.ok("강의를 조회했습니다.", lectureLookupInternalFacade.getLecture(lectureId));
    }

    // ids 개수 검증(@Size)은 Spec에 둔다. 구현 메서드가 매개변수 제약을 다시 정하면 Hibernate Validator가 막는다(HV000151)

    @Override
    @GetMapping
    public RsData<Map<Long, LectureLookupDto>> getLectures(@RequestParam(name = "ids") List<Long> ids) {
        return RsData.ok("강의를 조회했습니다.", lectureLookupInternalFacade.getLectures(ids));
    }
}
