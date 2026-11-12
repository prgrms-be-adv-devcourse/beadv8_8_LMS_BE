package com.hapbang.auxiliary.chatbot.in;

import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "챗봇 인플루언서 추천", description = "출연 상대로 어울리는 인플루언서 추천 (#719, 로그인 회원). 후보와 순서는 코드가 정하고, "
        + "LLM은 설명 경로에서 이유만 쓴다")
public interface ApiV1ChatbotInfluencerRecommendSpec {

    @Operation(summary = "인플루언서 추천(LLM 없음)", description = """
            추천 위젯용. 내 분야·자기소개와 함께 출연을 마친 상대들을 바탕으로 비슷한 인플루언서를 고르고, 출연 경험·리뷰 평가를 섞어 정렬한다. \
            개인 신호가 없으면 인기순이고 needsInterest=true다. 모자라면 인기순으로 채운다. 본인은 빠지고, 예전에 함께 출연한 상대는 \
            빠지지 않는다(PAST_PARTNER). 카드는 memberId로 회원 프로필 API를 불러 그린다. \
            요청 회원의 정보가 아직 동기화되지 않았거나(가입 직후) 탈퇴했으면 빈 목록이고 notice에 화면에 띄울 문구가 온다. \
            연타를 막으려고 회원마다 주기당 호출 수를 제한한다(설명 API보다 넉넉함, 넘으면 429).""")
    RsData<InfluencerRecommendResDto> recommend(@Parameter(hidden = true) Long userId);

    @Operation(summary = "인플루언서 추천 + 이유(LLM)", description = """
            챗봇 "인플루언서 추천받기" 버튼용. 후보는 위 API와 같고, 항목마다 explanation(추천 이유 문장)이 붙는다. \
            LLM이 실패하거나 시간 상한을 넘기거나 쓸 만한 답이 하나도 없으면 fallback=true이고 이유 코드의 정해진 문장으로 답한다\
            (후보는 그대로). LLM에는 회원 ID를 보내지 않는다. 범위 밖 후보·빈 문장은 버리고 그 후보는 정해진 문장으로 채운다. \
            회원마다 주기당 호출 수를 제한한다(넘으면 429).""")
    RsData<InfluencerRecommendResDto> recommendExplained(@Parameter(hidden = true) Long userId);
}
