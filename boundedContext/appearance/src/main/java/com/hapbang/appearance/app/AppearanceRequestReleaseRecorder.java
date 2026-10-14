package com.hapbang.appearance.app;

import java.time.LocalDateTime;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;

import lombok.RequiredArgsConstructor;

/**
 * 거절·응답 전 취소로 바뀐 요청서를 DB에 반영하고, 보류 HP 전액 반환 작업을 같은 트랜잭션에 기록한다.
 * 거절·취소 UseCase가 함께 쓴다. 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestReleaseRecorder {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final HpTaskSupport hpTaskSupport;

    /**
     * @return 기록한 반환 작업 ID
     * @throws AppearanceException 동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    public Long applyAndRecordRelease(AppearanceRequest request, HpTaskReason reason, LocalDateTime now) {
        try {
            appearanceRequestSupport.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            // 같은 요청서를 동시에 수락·거절·취소하면 먼저 반영된 쪽만 성공한다
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
        // 요청서 하나의 반환은 한 번뿐이라 순번은 1이다. 재시도해도 같은 반환 요청 ID를 쓴다.
        String releaseRequestId = HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, request.getId(),
                HpTaskType.RELEASE, 1);
        HpTask task = HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, request.getId(), request.getHostId(),
                request.getFee(), reason, releaseRequestId, now);
        return hpTaskSupport.save(task).getId();
    }
}
