package com.hapbang.appearance.app;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceEarningRule;
import com.hapbang.appearance.domain.AppearanceEarningRule.Condition;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.out.AppearanceEarningGroup;
import com.hapbang.appearance.out.AppearanceEarningRow;
import com.hapbang.appearance.out.AppearanceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 수익 조회용 저장소 접근 (#301). 분류 규칙의 조건({@link Condition})을 쿼리 파라미터로 옮긴다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceEarningSupport {

    private final AppearanceRepository appearanceRepository;

    public List<AppearanceEarningGroup> summarize(Long guestId) {
        return appearanceRepository.summarizeEarnings(guestId, AppearanceEarningRule.EARNING_TASK_TYPES);
    }

    /**
     * 조건이 비어 있으면 호출하지 않는다 (호출한 쪽에서 빈 페이지를 만든다).
     * 정렬은 쿼리에 고정돼 있어 요청의 정렬(sort)은 버리고 page·size만 쓴다. 정렬을 넘기면 Spring Data가 쿼리 뒤에 덧붙여,
     * 엔티티 속성이 아닌 이름(예: statusUpdatedAt)이면 쿼리 생성 예외가 나고 속성이면 순서가 바뀐다.
     */
    public Page<AppearanceEarningRow> find(Long guestId, Condition condition, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Collection<AppearanceStatus> plain = Condition.orPlaceholder(condition.plain(), AppearanceStatus.ESTABLISHED);
        Collection<AppearanceStatus> withTask = Condition.orPlaceholder(condition.withTask(), AppearanceStatus.CONFIRMED);
        Collection<HpTaskStatus> taskStatuses = Condition.orPlaceholder(condition.taskStatuses(), HpTaskStatus.DONE);
        boolean hasWithTask = !condition.withTask().isEmpty() && !condition.taskStatuses().isEmpty();
        boolean includeMissingTask = !condition.withTask().isEmpty() && condition.includeMissingTask();
        return appearanceRepository.findEarnings(guestId, AppearanceEarningRule.EARNING_TASK_TYPES,
                !condition.plain().isEmpty(), plain, hasWithTask, withTask, taskStatuses, includeMissingTask, unsorted);
    }
}
