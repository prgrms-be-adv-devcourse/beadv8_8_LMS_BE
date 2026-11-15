package com.hapbang.auxiliary.chatbot.in;

import java.util.List;

import com.hapbang.auxiliary.chatbot.app.dto.FaqHit;
import com.hapbang.auxiliary.chatbot.app.dto.FaqResDto;
import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Tag(name = "챗봇 FAQ", description = "챗봇 FAQ 모아보기·검색 (로그인 회원)")
public interface ApiV1ChatbotFaqSpec {

    @Operation(summary = "FAQ 모아보기", description = "분류별 FAQ 목록. 분류를 빼면 전체(분류·순서대로). 호출 제한 없음")
    RsData<List<FaqResDto>> list(@Parameter(description = "분류. 없으면 전체", example = "HP") FaqCategory category);

    @Operation(summary = "FAQ 검색", description = """
            질문과 뜻이 가까운 FAQ를 최대 top-k개(설정) 돌려준다(벡터 의미 검색, LLM 없음). score는 코사인 유사도이고 기준값 아래는 빠진다. \
            검색할 수 없으면(ES·임베딩 장애) 빈 목록이다. 대화와 합쳐 회원마다 주기당 호출 수를 제한한다(넘으면 429, Retry-After).""")
    RsData<List<FaqHit>> search(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "검색어(1~200자)", example = "충전한 HP 환불") @NotBlank @Size(max = 200) String q);
}
