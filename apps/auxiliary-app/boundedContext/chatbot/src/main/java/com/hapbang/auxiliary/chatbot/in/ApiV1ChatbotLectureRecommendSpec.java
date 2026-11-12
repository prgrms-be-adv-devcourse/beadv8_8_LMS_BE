package com.hapbang.auxiliary.chatbot.in;

import com.hapbang.auxiliary.chatbot.app.LectureRecommendUseCase;
import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.chatbot.in.dto.RecommendButtonRequest;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@Tag(name = "챗봇 강의 추천", description = "로그인 회원 (#718, #720). 구매·장바구니·리뷰 활동과 입력한 관심사로 고른 맞춤 강의. "
        + "위젯 API는 LLM을 부르지 않고, 버튼 API는 LLM이 후보 안에서 골라 이유를 쓴다.")
public interface ApiV1ChatbotLectureRecommendSpec {

    /**
     * 관심사: 앞뒤 공백을 뺀 길이가 1~{@link LectureRecommendUseCase#INTEREST_MAX_LENGTH}자.
     * 첫·마지막 글자가 공백 아닌 글자이고 그 사이가 최대 길이−2자다(앞뒤 공백은 몇 개든 된다).
     */
    String INTEREST_PATTERN = "(?s)\\s*\\S(?:.{0," + (LectureRecommendUseCase.INTEREST_MAX_LENGTH - 2) + "}\\S)?\\s*";
    String INTEREST_MESSAGE = "관심사는 앞뒤 공백을 빼고 1~" + LectureRecommendUseCase.INTEREST_MAX_LENGTH + "자로 써 주세요.";

    @Operation(summary = "맞춤 강의 추천",
            description = "추천 수와 판매자당 상한은 서버 설정이다. 이미 보유·결제 중·장바구니에 담은 강의와 본인 강의는 빠진다. "
                    + "지금은 맞춤으로 고를 수 없으면(활동 기록이 없거나 활동한 강의가 아직 색인 전) 인기 강의를 주고 "
                    + "needsInterest=true(관심사를 입력받아 다시 부른다). "
                    + "basis는 후보의 근거다(PERSONALIZED·POPULAR·POPULAR_TEMPORARY). 검색이 잠시 실패하면 관심사 요청도 오류 없이 "
                    + "인기 강의와 basis=POPULAR_TEMPORARY를 준다(FE는 이때만 다시 시도 안내). "
                    + "요청 회원 정보를 아직 받지 못했으면(가입 직후) 빈 목록과 notice(화면 문구)가 온다. "
                    + "회원마다 주기당 호출 수를 제한한다(모든 요청에 넓은 제한, interest를 넣은 요청은 좁은 제한도 함께. "
                    + "넘으면 429, Retry-After. FE는 위젯을 숨긴다). 다른 실패는 FE가 추천 영역만 숨긴다.")
    RsData<LectureRecommendResDto> recommend(Long userId,
            @Parameter(description = "관심사(앞뒤 공백을 뺀 1~" + LectureRecommendUseCase.INTEREST_MAX_LENGTH
                    + "자, 저장하지 않음). 없으면 활동 기록만으로", example = "데이터 분석 입문")
            @Pattern(regexp = INTEREST_PATTERN, message = INTEREST_MESSAGE) String interest);

    @Operation(summary = "강의 추천 + 이유(LLM, 챗봇 버튼)", description = "챗봇 '강의 추천받기' 버튼용. 본문 {interest?}. "
            + "관심사가 있으면 LLM이 비슷한 표현으로 늘려 함께 찾는다(실패하면 원문으로). 코드가 만든 맞춤 후보(최대 20개) 안에서 "
            + "LLM이 5개를 골라 이유를 쓰고, 모자라면 남은 후보(코드 순서) → 인기순으로 채운다. LLM이 고르지 않은 항목은 정해진 이유 문장이다. "
            + "LLM이 실패하거나 시간 상한을 넘기거나 쓸 만한 답이 없으면 fallback=true이고 코드 순서와 정해진 문장으로 답한다. "
            + "맞춤 후보가 없으면 LLM을 부르지 않는다. basis·needsInterest·notice는 위젯 API와 같다. "
            + "LLM에는 회원 정보·회원이 산 강의를 보내지 않는다. 회원마다 주기당 호출 수를 제한한다(넘으면 429).")
    RsData<LectureRecommendResDto> recommendExplained(Long userId, @Valid RecommendButtonRequest request);
}
