package com.hapbang.chat.app;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatIsolatedJobPage;
import com.hapbang.chat.app.dto.ChatReprocessResult;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 전달 작업 조회·운영 재처리 진입점. 관리자 API는 두 겹으로 막는다: global SecurityConfig의
 * {@code /api/v1/*}{@code /admin/**}(ADMIN)과 이 Facade의 {@code @PreAuthorize}. 트랜잭션은 열지 않는다.
 */
@Component
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class ChatDeliveryAdminFacade {

    private final ChatDeliveryAdminService chatDeliveryAdminService;

    @PreAuthorize(ChatRoles.ADMIN)
    public ChatIsolatedJobPage findIsolatedJobs(ChatJobStream stream, Long cursor, int size) {
        return chatDeliveryAdminService.findIsolatedJobs(stream, cursor, size);
    }

    @PreAuthorize(ChatRoles.ADMIN)
    public ChatReprocessResult reprocess(ChatJobStream stream, UUID jobId, Long adminId) {
        return chatDeliveryAdminService.reprocess(stream, jobId, adminId);
    }
}
