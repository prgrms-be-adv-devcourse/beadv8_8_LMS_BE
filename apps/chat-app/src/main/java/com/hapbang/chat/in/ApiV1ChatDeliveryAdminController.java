package com.hapbang.chat.in;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.app.ChatDeliveryAdminFacade;
import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.dto.ChatIsolatedJobPage;
import com.hapbang.chat.app.dto.ChatReprocessResult;
import com.hapbang.chat.global.auth.LoginUserId;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;
import com.hapbang.chat.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 격리 전달 작업 조회·운영 재처리 API. 전달 파이프라인을 켰을 때만 등록한다.
 */
@RestController
@RequestMapping("/api/v1/chats/admin/delivery-jobs")
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class ApiV1ChatDeliveryAdminController implements ApiV1ChatDeliveryAdminSpec {

    private final ChatDeliveryAdminFacade chatDeliveryAdminFacade;

    @Override
    @GetMapping
    public RsData<ChatIsolatedJobPage> getIsolatedJobs(
            @RequestParam(name = "stream", defaultValue = "DELIVERY") ChatJobStream stream,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return RsData.ok("격리한 전달 작업을 조회했습니다.",
                chatDeliveryAdminFacade.findIsolatedJobs(stream, cursor, size));
    }

    @Override
    @PostMapping("/{stream}/{jobId}/reprocess")
    public RsData<ChatReprocessResult> reprocess(@LoginUserId Long adminId, @PathVariable("stream") ChatJobStream stream,
            @PathVariable("jobId") UUID jobId) {
        return RsData.ok("전달 작업을 다시 처리하도록 되돌렸습니다.",
                chatDeliveryAdminFacade.reprocess(stream, jobId, adminId));
    }
}
