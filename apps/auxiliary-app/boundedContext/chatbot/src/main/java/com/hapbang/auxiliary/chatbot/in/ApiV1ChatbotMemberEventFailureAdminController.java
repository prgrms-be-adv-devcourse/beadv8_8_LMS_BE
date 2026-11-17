package com.hapbang.auxiliary.chatbot.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.MemberEventFailureAdminFacade;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventFailureResDto;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResDto;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;
import com.hapbang.auxiliary.chatbot.in.dto.MemberEventRetryReqDto;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 회원 이벤트 실패 관리 API (#976). 명세는 {@link ApiV1ChatbotMemberEventFailureAdminSpec}, 로직은 {@link MemberEventFailureAdminFacade}만 부른다 */
@RestController
@RequestMapping("/api/v1/chatbot/admin/member-event-failures")
@RequiredArgsConstructor
public class ApiV1ChatbotMemberEventFailureAdminController implements ApiV1ChatbotMemberEventFailureAdminSpec {

    private final MemberEventFailureAdminFacade memberEventFailureAdminFacade;

    @Override
    @GetMapping
    public RsData<Page<MemberEventFailureResDto>> getFailures(@LoginUserId Long adminId,
            @RequestParam(name = "status", defaultValue = "PENDING") MemberEventFailureStatus status, Pageable pageable) {
        return RsData.ok("회원 이벤트 실패 기록을 조회했습니다.",
                memberEventFailureAdminFacade.getFailures(adminId, status, pageable));
    }

    @Override
    @PostMapping("/retry")
    public RsData<List<MemberEventRetryResDto>> retry(@LoginUserId Long adminId,
            @Valid @RequestBody MemberEventRetryReqDto request) {
        return RsData.ok("회원 이벤트를 다시 처리했습니다.", memberEventFailureAdminFacade.retry(adminId, request.ids()));
    }
}
