package com.hapbang.auxiliary.chatbot.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;
import com.hapbang.auxiliary.chatbot.out.MemberCopyStore;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 관리자 API 3중 방어의 세 번째 겹 (#717). 다른 앱(출연 {@code HpReconciliationAdminFacade} 등)과 같이 보안 설정 → {@code @PreAuthorize}
 * 뒤에 회원 사본으로 지금도 정상 상태의 관리자인지 확인한다(토큰 발급 뒤 강등·탈퇴를 막는다).
 * <p>
 * 회원 사본에 없으면 거절한다. 첫 배포 직후에는 회원 이벤트 보존(72시간) 밖에 가입한 관리자가 첫 회원 싱크(배포 뒤 첫 매시 15분) 전까지 사본에 없어
 * 관리자 API를 쓸 수 없다. 길어야 한 시간이라 받아들이기로 했다(2026-11-09, 11/10 매시 싱크로 줄어듦).
 */
@Component
public class ChatbotAdminGuard {

    private final MemberCopyStore memberCopyStore;

    public ChatbotAdminGuard(MemberCopyStore memberCopyStore) {
        this.memberCopyStore = memberCopyStore;
    }

    /** @throws ChatbotException 회원 사본이 없거나, 관리자가 아니거나, 정상 상태가 아님({@code NOT_ADMIN}) */
    public void requireAdmin(Long adminId) {
        boolean admin = adminId != null && memberCopyStore.findRoleAndStatus(adminId)
                .filter(found -> found.role() == Role.ADMIN && found.status() == UserStatus.ACTIVE)
                .isPresent();
        if (!admin) {
            throw new ChatbotException(ChatbotErrorCode.NOT_ADMIN);
        }
    }
}
