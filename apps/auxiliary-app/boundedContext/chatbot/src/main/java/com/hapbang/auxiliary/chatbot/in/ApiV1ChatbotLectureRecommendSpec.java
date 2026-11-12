package com.hapbang.auxiliary.chatbot.in;

import com.hapbang.auxiliary.chatbot.app.LectureRecommendUseCase;
import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;

@Tag(name = "챗봇 강의 추천", description = "로그인 회원 (#718). 구매·장바구니·리뷰 활동과 입력한 관심사로 고른 맞춤 강의. LLM을 부르지 않는다.")
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
}
