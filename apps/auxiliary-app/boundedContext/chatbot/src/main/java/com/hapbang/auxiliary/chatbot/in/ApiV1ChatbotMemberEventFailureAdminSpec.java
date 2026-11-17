package com.hapbang.auxiliary.chatbot.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.auxiliary.chatbot.app.dto.MemberEventFailureResDto;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResDto;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;
import com.hapbang.auxiliary.chatbot.in.dto.MemberEventRetryReqDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "챗봇 회원 이벤트 실패 관리", description = "관리자 전용 (#976). 챗봇의 회원 이벤트 소비가 1분 간격 3번 재시도에도 실패해 DLT로 보낸 "
        + "이벤트를 보고 다시 처리한다. 특히 마스킹(탈퇴)은 이벤트로만 알 수 있어 다시 처리하지 않으면 그 회원 데이터가 챗봇 사본에 남는다. "
        + "관리자가 아니면 403. 토큰이 관리자여도 챗봇의 회원 사본에 정상 상태 관리자로 없으면 403 NOT_ADMIN.")
public interface ApiV1ChatbotMemberEventFailureAdminSpec {

    @Operation(summary = "실패 목록",
            description = "최신순. 상태를 생략하면 PENDING(처리 대기). 실패 이유는 예외 클래스·원인 클래스·메시지(이메일·전화를 지우고 200자로 자름. "
                    + "DB 예외는 SQLState만, JSON 읽기 예외는 없음)·시각이다. 이벤트 원문과 스택은 내보내지 않는다.")
    RsData<Page<MemberEventFailureResDto>> getFailures(@Parameter(hidden = true) Long adminId,
            @Parameter(description = "PENDING(처리 대기), RESOLVED(처리됨). 기본 PENDING") MemberEventFailureStatus status,
            Pageable pageable);

    @Operation(summary = "다시 처리",
            description = "고른 기록(최대 " + MemberEventFailure.RETRY_MAX_COUNT + "개)을, 고르지 않으면 다시 처리할 수 있는 처리 대기 기록을 "
                    + "오래된 순으로 한 번에 최대 " + MemberEventFailure.RETRY_MAX_COUNT + "건(서버 설정 기본값) 다시 처리하고 "
                    + "건별 결과를 요청 순서로 준다. 성공하면 RESOLVED로 바뀌어 기본 목록에서 빠진다. 같은 기록을 두 번 처리해도 결과가 같다"
                    + "(마스킹은 멱등, 가입·변경은 version이 더 새로운 사본을 덮지 않는다). 다른 관리자가 같은 기록을 처리 중이면 그 건은 IN_PROGRESS.")
    RsData<List<MemberEventRetryResDto>> retry(@Parameter(hidden = true) Long adminId, MemberEventRetryReqDto request);
}
