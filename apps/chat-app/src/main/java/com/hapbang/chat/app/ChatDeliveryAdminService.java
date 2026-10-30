package com.hapbang.chat.app;

import java.util.UUID;

import com.hapbang.chat.app.dto.ChatIsolatedJobPage;
import com.hapbang.chat.app.dto.ChatReprocessResult;

/**
 * 격리한 전달·제어 작업의 관리자 조회와 운영 재처리 (2026-10-30 사용자 결정).
 */
public interface ChatDeliveryAdminService {

    /** 조회 크기 상한 */
    int MAX_PAGE_SIZE = 100;

    /**
     * 격리한 작업을 체크포인트 ID 내림차순으로 조회한다.
     *
     * @param cursor 이전 응답의 nextCursor. 처음이면 null
     * @param size   1 이상 {@link #MAX_PAGE_SIZE} 이하
     */
    ChatIsolatedJobPage findIsolatedJobs(ChatJobStream stream, Long cursor, int size);

    /**
     * 격리한 작업을 운영 재처리한다. 처리 세대를 올리고 같은 안정 ID로 다시 적재하며, 요청한 관리자를 감사 기록에 남긴다.
     *
     * @throws com.hapbang.chat.app.exception.ChatException outbox 원본이 없으면 {@code DELIVERY_JOB_NOT_FOUND},
     *                                                      격리 상태가 아니면 {@code DELIVERY_JOB_NOT_ISOLATED}
     */
    ChatReprocessResult reprocess(ChatJobStream stream, UUID jobId, Long adminId);
}
