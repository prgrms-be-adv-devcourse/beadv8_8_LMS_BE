package com.hapbang.lecture.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureObjectionFacade;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;

import lombok.RequiredArgsConstructor;

/** 이의 제기 접수 내부 API (#278). Swagger 명세는 {@link ApiV1LectureObjectionInternalSpec}, 계약은 docs/api/internal-objections.md */
@RestController
@RequestMapping("/api/v1/internal/lectures/objections")
@RequiredArgsConstructor
public class ApiV1LectureObjectionInternalController implements ApiV1LectureObjectionInternalSpec {

    private final LectureObjectionFacade lectureObjectionFacade;

    @Override
    @PostMapping("/file")
    public RsData<LectureObjectionFiledDto> file(@RequestBody LectureObjectionFileRequest request) {
        return RsData.ok("이의 제기를 접수했습니다.", lectureObjectionFacade.file(request));
    }
}
