package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueDelivery;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class ProcessedOperationRepositoryTest {

    @Autowired
    private ProcessedOperationRepository processedOperationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 작업_ID로_찾고_같은_작업_ID는_두_번_넣을_수_없다() {
        IssueDelivery delivery = new IssueDelivery("req-1", 42L, MEMBER_ID, 1, NOW, NOW.plus(Duration.ofDays(30)));
        processedOperationRepository.saveAndFlush(ProcessedOperation.of(delivery, CouponIssueResult.ISSUED, NOW));
        entityManager.clear();

        assertThat(processedOperationRepository.findByOperationId("coupon-issue:42:req-1"))
                .get().satisfies(found -> {
                    assertThat(found.getResult()).isEqualTo("ISSUED");
                    assertThat(found.sameContent(delivery)).isTrue();
                });
        assertThatThrownBy(() -> processedOperationRepository.saveAndFlush(
                ProcessedOperation.of(delivery, CouponIssueResult.REJECTED, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(ProcessedOperationRepository.OPERATION_ID_UNIQUE);
    }

    @Test
    void 쿠폰_사용_작업_기록은_거절_코드를_detail에_남긴다() {
        processedOperationRepository.saveAndFlush(ProcessedOperation.ofWork("coupon-reserve:work-1", "m1|o2|abc",
                "REJECTED", "COUPON_GENERATION_MISMATCH", NOW));
        entityManager.clear();

        assertThat(processedOperationRepository.findByOperationId("coupon-reserve:work-1"))
                .get().satisfies(found -> {
                    assertThat(found.getDetail()).isEqualTo("COUPON_GENERATION_MISMATCH");
                    assertThat(found.sameContent("m1|o2|abc")).isTrue();
                });
    }
}
