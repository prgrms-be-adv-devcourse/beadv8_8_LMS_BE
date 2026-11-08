package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.WithdrawnRestorableException;
import com.hapbang.member.out.RestoreTicketStore;

/**
 * 본인 확인(비밀번호가 맞음, 구글 인증 성공)을 마친 회원이 로그인해도 되는지 정한다 (#593). 비밀번호·구글 로그인이 같은 규칙을 쓴다.
 * <ul>
 *     <li>정상 회원: 로그인</li>
 *     <li>탈퇴하고 복구 기한 안이며 탈퇴 기능이 켜져 있음: 복구 티켓을 만들고 409 {@code WITHDRAWN_RESTORABLE} ("복구할까요?")</li>
 *     <li>그 밖(정지, 복구 기한 지남, 탈퇴 기능 꺼짐): 403 {@code LOGIN_NOT_ALLOWED}</li>
 * </ul>
 * 탈퇴 기능이 꺼져 있으면 복구도 하지 않는다(복구는 탈퇴 때 닫은 지갑을 다시 열어야 해서 같은 연결 코드가 필요하다)
 */
@Service
public class MemberLoginGuard {

    private final ObjectProvider<MemberWithdrawalUseCase> memberWithdrawalUseCase;
    private final RestoreTicketStore restoreTicketStore;

    public MemberLoginGuard(ObjectProvider<MemberWithdrawalUseCase> memberWithdrawalUseCase,
                            RestoreTicketStore restoreTicketStore) {
        this.memberWithdrawalUseCase = memberWithdrawalUseCase;
        this.restoreTicketStore = restoreTicketStore;
    }

    /**
     * @throws WithdrawnRestorableException 복구할 수 있는 탈퇴 회원 (409, 복구 티켓)
     * @throws MemberException              그 밖에 정상 회원이 아님 ({@code LOGIN_NOT_ALLOWED})
     */
    public void check(User user) {
        if (user.isActive()) {
            return;
        }
        if (user.canRestore(LocalDateTime.now()) && memberWithdrawalUseCase.getIfAvailable() != null) {
            throw new WithdrawnRestorableException(restoreTicketStore.issue(user.getId()), user.restorableUntil());
        }
        throw new MemberException(MemberErrorCode.LOGIN_NOT_ALLOWED);
    }
}
