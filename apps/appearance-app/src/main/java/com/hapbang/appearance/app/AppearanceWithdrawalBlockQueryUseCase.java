package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.AppearanceWithdrawalBlock;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴가 막힌 출연 쪽 이유 (#624). 회원 앱이 탈퇴 전에 내부 API로 부른다 (docs/api/internal-withdrawals.md 3장).
 * 트랜잭션은 호출한 {@link AppearanceWithdrawalInternalFacade}가 연다 (읽기 전용). 아무것도 바꾸지 않는다.
 * <ul>
 *     <li>회원이 호스트로도 게스트로도 관련될 수 있어 양쪽을 본다. 조건마다 건수를 세어 이유 하나를 만든다 (건마다 만들지 않는다).</li>
 *     <li>날짜({@code availableAt})는 주지 않는다. 진행 중인 출연도 끝날 날을 약속하지 않는다 (ISSUE.md APR-49 WB2).</li>
 *     <li>회원 복제를 확인하지 않는다. 복제 회원 정보가 없거나 출연 기록이 없는 회원은 빈 목록이다.</li>
 *     <li>확인과 지갑 닫기 사이 빈틈(그 사이 새 보류·수락)은 막지 않는다. 알려진 한계이며, 나중에 들어온 HP는 닫힌 지갑에 남고
 *     지갑의 닫힌 지갑 입금 로그·지표로 찾는다 (WB4).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceWithdrawalBlockQueryUseCase {

    private static final List<AppearanceStatus> IN_PROGRESS =
            List.of(AppearanceStatus.ESTABLISHED, AppearanceStatus.CANCEL_REQUESTED);

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceSupport appearanceSupport;
    private final HpTaskSupport hpTaskSupport;

    /**
     * @param userId 탈퇴하려는 회원 ID
     * @throws AppearanceException 회원 ID가 1 미만일 때({@code INVALID_REQUEST})
     */
    public WithdrawalBlocksDto find(Long userId) {
        if (userId == null || userId < 1) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "회원 ID는 1 이상이어야 합니다.");
        }
        List<WithdrawalBlockReasonDto> reasons = new ArrayList<>();
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_REQUEST_SENT,
                appearanceRequestSupport.countSentByHost(userId, AppearanceRequestStatus.REQUESTED));
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_REQUEST_RECEIVED,
                appearanceRequestSupport.countReceivedByGuest(userId, AppearanceRequestStatus.REQUESTED));
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_RECRUITING,
                appearanceRecruitSupport.countByHost(userId, AppearanceRecruitStatus.RECRUITING));
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_APPLICATION_PENDING,
                appearanceApplicationSupport.countByGuest(userId, AppearanceApplicationStatus.APPLIED));
        // 출연 취소 요청이 처리 중인 출연(CANCEL_REQUESTED)도 진행 중으로 센다
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_IN_PROGRESS,
                appearanceSupport.countByParticipant(userId, IN_PROGRESS));
        // 보류 결과를 기다리는 요청서·모집글(호스트)과 아직 끝나지 않은 HP 작업(반환·위약금·지급)
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_HP_PROCESSING,
                appearanceRequestSupport.countSentByHost(userId, AppearanceRequestStatus.HOLD_PENDING)
                        + appearanceRecruitSupport.countByHost(userId, AppearanceRecruitStatus.HOLD_PENDING)
                        + hpTaskSupport.countByParticipant(userId, HpTaskStatus.PENDING));
        // 격리된 HP 작업: 운영자가 다시 시도하면 HP가 움직이므로 확인이 끝날 때까지 막는다 (WB3)
        add(reasons, AppearanceWithdrawalBlock.APPEARANCE_HP_CHECK_REQUIRED,
                hpTaskSupport.countByParticipant(userId, HpTaskStatus.DEAD));
        return new WithdrawalBlocksDto(reasons);
    }

    private static void add(List<WithdrawalBlockReasonDto> reasons, AppearanceWithdrawalBlock block, long count) {
        if (count > 0) {
            reasons.add(WithdrawalBlockReasonDto.of(block.name(), block.message(count)));
        }
    }
}
