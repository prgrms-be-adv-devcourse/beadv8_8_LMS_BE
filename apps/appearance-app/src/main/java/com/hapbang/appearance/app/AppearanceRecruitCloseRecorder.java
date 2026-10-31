package com.hapbang.appearance.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;

import lombok.RequiredArgsConstructor;

/**
 * 마감으로 바뀐 모집글의 뒷정리를 같은 트랜잭션에서 한다. 조기 마감·기한 마감 배치가 함께 쓴다. 트랜잭션은 호출한 쪽이 연다.
 * <ol>
 *     <li>대기 중인 지원을 모두 미선정({@code NOT_SELECTED})으로 (ISSUE.md APR-28 C2)</li>
 *     <li>모집글·지원 변경을 바로 반영해 동시 수락·지원 취소와의 충돌을 여기서 확인</li>
 *     <li>성립하지 못한 인원분 반환 작업 기록. 정원이 다 찼으면(반환 0) 기록하지 않는다 (C3, C7)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitCloseRecorder {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final HpTaskSupport hpTaskSupport;

    /**
     * @return 기록한 반환 작업 ID. 돌려줄 금액이 없으면 null
     * @throws AppearanceException 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public Long closeApplicationsAndRecordRelease(AppearanceRecruit recruit, LocalDateTime now) {
        for (AppearanceApplication application : appearanceApplicationSupport.findPendingByRecruit(recruit.getId())) {
            application.closeUnselected();
        }
        appearanceRecruitSupport.flushOrConflict();

        long unfilledFee = recruit.unfilledFee();
        if (unfilledFee == 0) {
            return null;
        }
        // 마감 반환은 모집글당 한 번이라 순번은 1이다. 재시도해도 같은 반환 요청 ID를 쓴다.
        // TODO(모집 취소·출연 취소 요청): 같은 모집글 보류에서 반환이 더 생기면 순번(또는 동작 이름)을 나눠 겹치지 않게 한다.
        String releaseRequestId = HpTask.requestId(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(),
                HpTaskType.RELEASE, 1);
        HpTask task = HpTask.release(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(), recruit.getHostId(),
                unfilledFee, HpTaskReason.RECRUIT_CLOSED, releaseRequestId, now);
        return hpTaskSupport.save(task).getId();
    }
}
