package com.hapbang.auxiliary.chatbot.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotFaqFacade;
import com.hapbang.auxiliary.chatbot.app.FaqHit;
import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FAQ 모아보기·검색(#715). LLM을 부르지 않는다. Tool 객체({@code FaqTools})가 아니라 Facade·서비스를 부른다(교안 규칙).
 * 로그인하지 않은 요청은 SecurityConfig가 401로 막는다. 검색어 제약은 Spring MVC 기본 메서드 검증(400 INVALID_REQUEST)으로 본다.
 * {@code @Validated}를 붙이면 AOP 검증이 먼저 돌아 공통 처리기가 모르는 예외(500)가 나므로 붙이지 않는다.
 */
@Tag(name = "Chatbot FAQ", description = "챗봇 FAQ 모아보기·검색 (로그인 회원)")
@RestController
@RequestMapping("/api/v1/chatbot/faqs")
@RequiredArgsConstructor
public class ApiV1ChatbotFaqController {

    private final ChatbotFaqFacade facade;

    @Operation(summary = "FAQ 모아보기", description = "분류별 FAQ 목록. 분류를 빼면 전체(분류·순서대로). 호출 제한 없음")
    @GetMapping
    public RsData<List<ChatbotFaqFacade.FaqView>> list(
            @Parameter(description = "분류. 없으면 전체", example = "HP") @RequestParam(required = false) FaqCategory category) {
        return RsData.ok("FAQ 목록을 조회했습니다.", facade.listFaqs(category));
    }

    @Operation(summary = "FAQ 검색", description = """
            질문과 뜻이 가까운 FAQ를 최대 top-k개(설정) 돌려준다(벡터 의미 검색, LLM 없음). score는 코사인 유사도이고 기준값 아래는 빠진다. \
            검색할 수 없으면(ES·임베딩 장애) 빈 목록이다. 대화와 합쳐 회원마다 주기당 호출 수를 제한한다(넘으면 429, Retry-After).""")
    @GetMapping("/search")
    public RsData<List<FaqHit>> search(
            @LoginUserId Long userId,
            @Parameter(description = "검색어(1~200자)", example = "충전한 HP 환불") @RequestParam @NotBlank @Size(max = 200) String q) {
        return RsData.ok("FAQ를 검색했습니다.", facade.searchFaqs(userId, q));
    }
}
