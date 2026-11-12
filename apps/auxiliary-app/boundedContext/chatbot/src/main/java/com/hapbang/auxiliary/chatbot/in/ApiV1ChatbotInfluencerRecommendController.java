package com.hapbang.auxiliary.chatbot.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotInfluencerRecommendFacade;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.chatbot.in.dto.RecommendButtonRequest;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 인플루언서 추천 (#719, #720). 관심사는 버튼 API 본문과 자유 질문 Tool만 받는다(위젯은 받지 않는다) */
@RestController
@RequestMapping("/api/v1/chatbot/recommendations/influencers")
@RequiredArgsConstructor
public class ApiV1ChatbotInfluencerRecommendController implements ApiV1ChatbotInfluencerRecommendSpec {

    private final ChatbotInfluencerRecommendFacade facade;

    @Override
    @GetMapping
    public RsData<InfluencerRecommendResDto> recommend(@LoginUserId Long userId) {
        return RsData.ok("인플루언서 추천입니다.", facade.recommend(userId));
    }

    @Override
    @PostMapping("/explained")
    public RsData<InfluencerRecommendResDto> recommendExplained(@LoginUserId Long userId,
            @RequestBody(required = false) RecommendButtonRequest request) {
        return RsData.ok("인플루언서 추천과 이유입니다.",
                facade.recommendExplained(userId, RecommendButtonRequest.interestOf(request)));
    }
}
