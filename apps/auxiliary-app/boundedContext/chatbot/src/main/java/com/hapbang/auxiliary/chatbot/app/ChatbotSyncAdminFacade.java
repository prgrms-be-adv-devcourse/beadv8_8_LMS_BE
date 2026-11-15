package com.hapbang.auxiliary.chatbot.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.chatbot.app.dto.SyncRunDetailResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunStartResDto;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;
import com.hapbang.auxiliary.chatbot.out.SyncMismatchRepository;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;

import lombok.RequiredArgsConstructor;

/**
 * 챗봇 싱크 관리 API 진입점 (관리자, #716). 컨트롤러는 이 Facade만 부른다.
 * <p>
 * 권한은 다른 앱과 같은 3중 방어다: 보안 설정({@code /api/v1/*}{@code /admin/**} ADMIN) → {@code @PreAuthorize} →
 * 회원 사본으로 지금도 정상 상태의 관리자인지 확인({@link ChatbotAdminGuard}, #717).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChatbotSyncAdminFacade {

    private final ChatbotAdminGuard chatbotAdminGuard;
    private final ChatbotSyncUseCase chatbotSyncUseCase;
    private final SyncRunRepository syncRunRepository;
    private final SyncMismatchRepository syncMismatchRepository;

    /**
     * 지금 실행한다. 회차를 만들고 바로 돌아오며 실행은 비동기다. 트랜잭션을 열지 않는다.
     *
     * @throws ChatbotException 관리자 자격 없음({@code NOT_ADMIN}), 같은 종류가 실행 중({@code SYNC_ALREADY_RUNNING}),
     *                          락을 시도하지 못함({@code SYNC_LOCK_UNAVAILABLE})
     */
    public SyncRunStartResDto start(SyncType type, Long adminId) {
        chatbotAdminGuard.requireAdmin(adminId);
        return new SyncRunStartResDto(chatbotSyncUseCase.startManual(type, adminId).getId());
    }

    /**
     * 회차 목록(최신순). 종류를 생략하면 모든 종류
     *
     * @throws ChatbotException 관리자 자격 없음({@code NOT_ADMIN})
     */
    @Transactional(readOnly = true)
    public Page<SyncRunResDto> getRuns(Long adminId, SyncType type, Pageable pageable) {
        chatbotAdminGuard.requireAdmin(adminId);
        Page<SyncRun> runs = type == null
                ? syncRunRepository.findByOrderByStartedAtDescIdDesc(pageable)
                : syncRunRepository.findByTypeOrderByStartedAtDescIdDesc(type, pageable);
        return runs.map(SyncRunResDto::from);
    }

    /**
     * 회차 상세와 어긋난 항목.
     *
     * @throws ChatbotException 관리자 자격 없음({@code NOT_ADMIN}), 회차 없음({@code SYNC_RUN_NOT_FOUND})
     */
    @Transactional(readOnly = true)
    public SyncRunDetailResDto getRun(Long adminId, Long runId) {
        chatbotAdminGuard.requireAdmin(adminId);
        SyncRun run = syncRunRepository.findById(runId)
                .orElseThrow(() -> new ChatbotException(ChatbotErrorCode.SYNC_RUN_NOT_FOUND));
        return SyncRunDetailResDto.from(run, syncMismatchRepository.findByRunIdOrderByIdAsc(runId));
    }
}
