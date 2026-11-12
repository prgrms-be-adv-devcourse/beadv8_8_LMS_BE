package com.hapbang.auxiliary.chatbot.app;

import java.util.Collection;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 모델이 부르는 FAQ Tool(#715, 교안 7장 규칙).
 * <ul>
 *     <li>설명은 구체적으로 쓴다. 모델은 이 설명만 보고 부를지 정한다.</li>
 *     <li>값({@code List})을 돌려준다. Flux·Mono를 돌려주면 모델이 결과 대신 객체 껍데기를 받는다.</li>
 *     <li>일반 API(컨트롤러)는 이 객체를 쓰지 않고 {@link FaqSearchService}를 부른다(Tool 서비스와 일반 API 분리).</li>
 *     <li>찾은 FAQ는 요청마다 넘긴 {@link ToolContext}의 목록({@link ChatToolContext#FAQ_SOURCES})에 담는다. 응답의 {@code sources}가
 *     된다. ToolContext는 모델에게 보이지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FaqTools {

    static final String TOOL_NAME = "searchFaq";

    private final FaqSearchService searchService;
    private final ChatbotMetrics metrics;

    @Tool(name = TOOL_NAME, description = """
            합방 서비스 이용 방법과 정책을 묻는 질문에 쓴다. 회원가입·로그인, 인플루언서 채널 인증, HP 충전·환불, \
            수익·환전, 출연 요청·모집·취소·노쇼, 강의 구매·환불·판매, 채팅, 고객센터·이의 제기, 탈퇴 등에 대한 FAQ를 \
            뜻이 가까운 순서로 최대 몇 개 돌려준다. 결과가 비어 있으면 FAQ에 답이 없는 질문이다.""")
    public List<FaqHit> searchFaq(
            @ToolParam(description = "사용자의 질문을 한 문장으로. 예: 'HP 충전은 어떻게 해요?', '출연 취소하면 HP 돌려받나요?'")
            String question,
            ToolContext toolContext) {
        Collection<FaqHit> sources = ChatToolContext.faqSources(toolContext);
        ChatToolContext.cards(toolContext, TOOL_NAME).called(TOOL_NAME);
        metrics.toolCall(TOOL_NAME);
        List<FaqHit> hits = searchService.search(question);
        sources.addAll(hits);
        return hits;
    }
}
