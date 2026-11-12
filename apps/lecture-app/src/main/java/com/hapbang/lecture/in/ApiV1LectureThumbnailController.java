package com.hapbang.lecture.in;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureFacade;
import com.hapbang.lecture.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 강의 썸네일 URL 일괄 조회 (#885). 강의 검색 결과처럼 강의 ID만 아는 화면이 썸네일을 채울 때 부른다(검색은 강의 S3 파일을 다루지 않음, L4).
 * 비회원도 허용한다(SecurityConfig). 회원 앱 프로필 사진 일괄 조회(#770)와 같은 모양이다
 */
@RestController
@RequestMapping("/api/v1/lectures")
@RequiredArgsConstructor
public class ApiV1LectureThumbnailController implements ApiV1LectureThumbnailSpec {

    private final LectureFacade lectureFacade;

    // ids 개수 검증(@Size)은 Spec에 둔다. 구현 메서드가 매개변수 제약을 다시 정하면 Hibernate Validator가 막는다(HV000151)

    @Override
    @GetMapping("/thumbnails")
    public RsData<Map<Long, String>> getThumbnailUrls(@RequestParam(name = "ids") List<Long> ids) {
        return RsData.ok("강의 썸네일 URL을 조회했습니다.", lectureFacade.getThumbnailUrls(ids));
    }
}
