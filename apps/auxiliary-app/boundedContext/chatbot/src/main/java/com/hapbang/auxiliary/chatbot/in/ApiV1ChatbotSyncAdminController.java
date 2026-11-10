package com.hapbang.auxiliary.chatbot.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.chatbot.app.ChatbotSyncAdminFacade;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunDetailResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunStartResDto;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 챗봇 싱크 관리 API (#716). 명세는 {@link ApiV1ChatbotSyncAdminSpec}, 로직은 {@link ChatbotSyncAdminFacade}만 부른다 */
@RestController
@RequestMapping("/api/v1/chatbot/admin/sync")
@RequiredArgsConstructor
public class ApiV1ChatbotSyncAdminController implements ApiV1ChatbotSyncAdminSpec {

    private final ChatbotSyncAdminFacade chatbotSyncAdminFacade;

    @Override
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RsData<SyncRunStartResDto> start(@LoginUserId Long adminId, @RequestParam("type") SyncType type) {
        return RsData.of(HttpStatus.ACCEPTED, "동기화를 시작했습니다.", chatbotSyncAdminFacade.start(type, adminId));
    }

    @Override
    @GetMapping("/runs")
    public RsData<Page<SyncRunResDto>> getRuns(@LoginUserId Long adminId,
            @RequestParam(name = "type", required = false) SyncType type, Pageable pageable) {
        return RsData.ok("동기화 실행 기록을 조회했습니다.", chatbotSyncAdminFacade.getRuns(adminId, type, pageable));
    }

    @Override
    @GetMapping("/runs/{runId}")
    public RsData<SyncRunDetailResDto> getRun(@LoginUserId Long adminId, @PathVariable("runId") Long runId) {
        return RsData.ok("동기화 실행 기록을 조회했습니다.", chatbotSyncAdminFacade.getRun(adminId, runId));
    }
}
