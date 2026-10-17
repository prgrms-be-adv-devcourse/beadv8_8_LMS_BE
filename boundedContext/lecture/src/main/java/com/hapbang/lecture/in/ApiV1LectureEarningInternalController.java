package com.hapbang.lecture.in;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.LectureEarningInternalFacade;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

import lombok.RequiredArgsConstructor;

/** 강의 판매 수익 내부 API (#300). Swagger 명세는 {@link ApiV1LectureEarningInternalSpec}, 계약은 docs/api/internal-earnings.md */
@RestController
@RequestMapping("/api/v1/internal/lectures/earnings")
@RequiredArgsConstructor
public class ApiV1LectureEarningInternalController implements ApiV1LectureEarningInternalSpec {

    private final LectureEarningInternalFacade lectureEarningInternalFacade;

    @Override
    @GetMapping("/summary")
    public RsData<LectureEarningSummaryDto> getSummary(@RequestParam("userId") Long userId) {
        return RsData.ok("판매 수익 요약을 조회했습니다.", lectureEarningInternalFacade.getSummary(userId));
    }

    @Override
    @GetMapping
    public RsData<Page<LectureEarningItemDto>> getEarnings(@RequestParam("userId") Long userId,
            @RequestParam(name = "status", required = false) Set<LectureEarningStatus> statuses,
            Pageable pageable) {
        return RsData.ok("판매 수익 목록을 조회했습니다.",
                lectureEarningInternalFacade.getEarnings(userId, statuses, pageable));
    }
}
