package com.hapbang.auxiliary.chatbot.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotFaqFacade;
import com.hapbang.auxiliary.chatbot.app.dto.FaqHit;
import com.hapbang.auxiliary.chatbot.app.dto.FaqResDto;
import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * FAQ 모아보기·검색(#715). 명세는 {@link ApiV1ChatbotFaqSpec}에 있다. LLM을 부르지 않는다. Tool 객체({@code FaqTools})가 아니라
 * Facade·서비스를 부른다(교안 규칙). 로그인하지 않은 요청은 SecurityConfig가 401로 막는다. 검색어 제약(Spec에 있다)은 Spring MVC 기본
 * 메서드 검증(400 INVALID_REQUEST)으로 본다. {@code @Validated}를 붙이면 AOP 검증이 먼저 돌아 공통 처리기가 모르는 예외(500)가 나므로
 * 붙이지 않는다.
 */
@RestController
@RequestMapping("/api/v1/chatbot/faqs")
@RequiredArgsConstructor
public class ApiV1ChatbotFaqController implements ApiV1ChatbotFaqSpec {

    private final ChatbotFaqFacade facade;

    @Override
    @GetMapping
    public RsData<List<FaqResDto>> list(@RequestParam(required = false) FaqCategory category) {
        return RsData.ok("FAQ 목록을 조회했습니다.", facade.listFaqs(category));
    }

    @Override
    @GetMapping("/search")
    public RsData<List<FaqHit>> search(@LoginUserId Long userId, @RequestParam String q) {
        return RsData.ok("FAQ를 검색했습니다.", facade.searchFaqs(userId, q));
    }
}
