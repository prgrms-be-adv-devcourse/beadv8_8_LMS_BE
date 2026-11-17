package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.chatbot.app.dto.MemberEventFailureResDto;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResDto;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;
import com.hapbang.auxiliary.chatbot.out.MemberEventFailureRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 실패 관리 API 진입점 (관리자, #976). 컨트롤러는 이 Facade만 부른다.
 * 권한은 싱크 관리와 같은 3중 방어다({@link ChatbotSyncAdminFacade}).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize(ChatbotRoles.ADMIN)
public class MemberEventFailureAdminFacade {

    private final ChatbotAdminGuard chatbotAdminGuard;
    private final MemberEventFailureRepository memberEventFailureRepository;
    private final MemberEventFailureRetryUseCase memberEventFailureRetryUseCase;

    /**
     * 실패 기록 목록(최신순)
     *
     * @throws com.hapbang.auxiliary.chatbot.app.exception.ChatbotException 관리자 자격 없음({@code NOT_ADMIN})
     */
    @Transactional(readOnly = true)
    public Page<MemberEventFailureResDto> getFailures(Long adminId, MemberEventFailureStatus status, Pageable pageable) {
        chatbotAdminGuard.requireAdmin(adminId);
        return memberEventFailureRepository.findByStatusOrderByIdDesc(status, pageable).map(MemberEventFailureResDto::from);
    }

    /**
     * 다시 처리한다. 건마다 트랜잭션을 따로 연다(여기서는 열지 않는다).
     *
     * @param ids 비어 있으면 처리 대기 기록 전체(상한까지)
     * @throws com.hapbang.auxiliary.chatbot.app.exception.ChatbotException 관리자 자격 없음({@code NOT_ADMIN})
     */
    public List<MemberEventRetryResDto> retry(Long adminId, List<Long> ids) {
        chatbotAdminGuard.requireAdmin(adminId);
        return memberEventFailureRetryUseCase.retry(ids, adminId);
    }
}
