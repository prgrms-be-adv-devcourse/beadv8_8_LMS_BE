package com.hapbang.chat.app;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hapbang.chat.app.dto.ChatIsolatedJob;
import com.hapbang.chat.app.dto.ChatIsolatedJobPage;
import com.hapbang.chat.app.dto.ChatReprocessResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChatDeliveryAdminService} 구현. 조회는 {@link ChatOutboxStore}, 재처리는 {@link ChatStreamReconstructor}에 맡긴다.
 * 재처리는 DB 트랜잭션 밖에서 부른다(재처리 안에서 짧은 트랜잭션을 열고, 큐 적재는 커밋 뒤에 한다).
 */
@Slf4j
@Service
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class DefaultChatDeliveryAdminService implements ChatDeliveryAdminService {

    private final ChatOutboxStore chatOutboxStore;
    private final ChatStreamReconstructor reconstructor;

    @Override
    public ChatIsolatedJobPage findIsolatedJobs(ChatJobStream stream, Long cursor, int size) {
        if (stream == null || size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "stream이 필요하고, size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
        }
        List<ChatIsolatedJob> found = chatOutboxStore.findIsolatedJobs(stream, cursor, size + 1);
        if (found.size() <= size) {
            return new ChatIsolatedJobPage(found, null);
        }
        List<ChatIsolatedJob> page = found.subList(0, size);
        return new ChatIsolatedJobPage(page, page.getLast().checkpointId());
    }

    @Override
    public ChatReprocessResult reprocess(ChatJobStream stream, UUID jobId, Long adminId) {
        if (stream == null || jobId == null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
        if (chatOutboxStore.findJob(stream, jobId).isEmpty()) {
            // 형식 오류·원본 누락으로 새로 만든 격리 행은 outbox 원본이 없어 재처리할 수 없다(운영자 조사 전용)
            throw new ChatException(ChatErrorCode.DELIVERY_JOB_NOT_FOUND);
        }
        if (!reconstructor.reprocess(stream, jobId, adminId)) {
            throw new ChatException(ChatErrorCode.DELIVERY_JOB_NOT_ISOLATED);
        }
        log.warn("관리자 운영 재처리 stream={}, jobId={}, adminId={}", stream, jobId, adminId);
        return new ChatReprocessResult(stream, jobId, adminId);
    }
}
