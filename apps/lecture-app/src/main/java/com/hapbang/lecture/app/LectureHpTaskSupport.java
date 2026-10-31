package com.hapbang.lecture.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.LectureHpTaskSearch;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.criteria.Predicate;

import lombok.RequiredArgsConstructor;

/** 강의 HP 작업 등록·조회 (D57) */
@Component
@RequiredArgsConstructor
public class LectureHpTaskSupport {

    private final LectureHpTaskRepository lectureHpTaskRepository;

    /**
     * 작업을 업무 상태와 같은 트랜잭션에 등록한다(트랜잭션이 없으면 실패한다). 실행은 커밋 뒤 실행기가 한다.
     * 같은 요청 ID의 작업이 있으면 요청 값이 같을 때 그 작업을 돌려주고, 다르면 충돌이다 (D57 (2)).
     * 동시에 같은 요청 ID를 등록하면 유일 제약 위반으로 늦은 쪽 트랜잭션이 실패한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LectureHpTask register(LectureHpTask task) {
        Optional<LectureHpTask> existing = lectureHpTaskRepository.findByRequestId(task.getRequestId());
        if (existing.isEmpty()) {
            return lectureHpTaskRepository.save(task);
        }
        if (!existing.get().hasSamePayload(task)) {
            throw new IllegalStateException("같은 요청 ID로 다른 HP 작업을 만들 수 없습니다. requestId=" + task.getRequestId());
        }
        return existing.get();
    }

    public LectureHpTask getById(Long taskId) {
        return lectureHpTaskRepository.findById(taskId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.HP_TASK_NOT_FOUND));
    }

    /**
     * 관리자 목록. 최신 작업순으로 고정한다(정렬 요청은 무시한다).
     * 강의 작업의 지갑 참조 유형은 항상 주문 항목이라, 다른 참조 유형으로 거르면 결과가 없다.
     */
    public Page<LectureHpTask> search(LectureHpTaskSearch search, Pageable pageable) {
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));
        if (search.referenceType() != null && search.referenceType() != BillingReferenceType.LECTURE_ORDER_ITEM) {
            return Page.empty(latestFirst);
        }
        return lectureHpTaskRepository.findAll(matching(search), latestFirst);
    }

    private static Specification<LectureHpTask> matching(LectureHpTaskSearch search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search.status() != null) {
                predicates.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.taskType() != null) {
                predicates.add(cb.equal(root.get("taskType"), search.taskType()));
            }
            if (search.orderId() != null) {
                predicates.add(cb.equal(root.get("orderId"), search.orderId()));
            }
            if (search.orderItemId() != null) {
                predicates.add(cb.equal(root.get("orderItemId"), search.orderItemId()));
            }
            if (search.errorCode() != null) {
                predicates.add(cb.equal(root.get("lastErrorCode"), search.errorCode()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
