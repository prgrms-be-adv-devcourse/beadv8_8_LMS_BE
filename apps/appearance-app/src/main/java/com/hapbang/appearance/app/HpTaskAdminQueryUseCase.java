package com.hapbang.appearance.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.domain.HpTaskStatus;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 HP 작업 조회 (#397). 다시 시도처럼 트랜잭션 밖에서 실행한 뒤 결과를 읽을 때도 한 읽기 전용 트랜잭션에서 읽도록,
 * 조회 메서드가 스스로 읽기 전용 트랜잭션을 연다 (호출한 쪽 트랜잭션이 있으면 합류).
 */
@Service
@RequiredArgsConstructor
public class HpTaskAdminQueryUseCase {

    private final HpTaskSupport hpTaskSupport;

    /** 상태별 작업 목록. 마지막으로 바뀐 작업부터 */
    @Transactional(readOnly = true)
    public Page<HpTaskAdminResDto> getTasks(HpTaskStatus status, Pageable pageable) {
        return hpTaskSupport.findByStatus(status, pageable).map(HpTaskAdminResDto::from);
    }

    /**
     * 작업 한 건.
     *
     * @throws com.hapbang.appearance.domain.AppearanceException 작업 없음({@code HP_TASK_NOT_FOUND})
     */
    @Transactional(readOnly = true)
    public HpTaskAdminResDto getTask(Long taskId) {
        return HpTaskAdminResDto.from(hpTaskSupport.getForAdmin(taskId));
    }
}
