package com.hapbang.auxiliary.chatbot.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotLectureRecommendFacade;
import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.chatbot.in.dto.RecommendButtonReqDto;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 강의 추천 API (#718). Tool 객체({@code LectureRecommendTools})가 아니라 Facade를 부른다.
 * 로그인하지 않은 요청은 SecurityConfig가 401로 막는다. 관심사 제약은 Spec의 애노테이션으로 Spring MVC 기본 메서드 검증(400)이 본다.
 */
@RestController
@RequestMapping("/api/v1/chatbot/recommendations/lectures")
@RequiredArgsConstructor
public class ApiV1ChatbotLectureRecommendController implements ApiV1ChatbotLectureRecommendSpec {

    private final ChatbotLectureRecommendFacade facade;

    @Override
    @GetMapping
    public RsData<LectureRecommendResDto> recommend(@LoginUserId Long userId,
            @RequestParam(name = "interest", required = false) String interest) {
        return RsData.ok("강의를 추천했습니다.", facade.recommend(userId, interest));
    }

    @Override
    @PostMapping("/explained")
    public RsData<LectureRecommendResDto> recommendExplained(@LoginUserId Long userId,
            @RequestBody(required = false) RecommendButtonReqDto request) {
        return RsData.ok("강의를 추천하고 이유를 붙였습니다.",
                facade.recommendExplained(userId, RecommendButtonReqDto.interestOf(request)));
    }
}
