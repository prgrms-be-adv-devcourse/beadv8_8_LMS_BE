package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// 지급 실패 기록(D54)을 PAYOUT 작업으로 옮기는 마이그레이션 (#277, D59 (7), D70 (4)). 테스트 DB는 비어 있는 채로
// 마이그레이션되므로, 옛 실패 기록을 넣은 뒤 같은 SQL 파일을 직접 실행한다
@RepositoryTest
class PayoutFailureMigrationTest {

    private static final String MIGRATION =
            "db/migration/V202610301001__migrate_lecture_payout_failures_to_hp_tasks.sql";
    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 10, 3, 1, 0);

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 지급_실패_기록이_있는_구매_확정_항목을_지급_처리_중과_PAYOUT_작업으로_옮긴다() throws IOException {
        OrderItem quarantined = confirmedItem(20L);
        recordLegacyFailure(quarantined, 2, "PAYOUT_ALREADY_SETTLED", true);
        OrderItem failedOnce = confirmedItem(30L);
        recordLegacyFailure(failedOnce, 1, "WALLET_NOT_FOUND", false);
        OrderItem neverFailed = confirmedItem(40L);
        entityManager.clear();

        runMigration();

        assertThat(statusOf(quarantined)).isEqualTo(OrderItemStatus.PAYOUT_PENDING);
        assertThat(statusOf(failedOnce)).isEqualTo(OrderItemStatus.PAYOUT_PENDING);
        assertThat(statusOf(neverFailed)).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(lectureHpTaskRepository.findAll())
                .extracting(LectureHpTask::getOrderItemId, LectureHpTask::getStatus, LectureHpTask::getAttemptCount,
                        LectureHpTask::getTotalAttemptCount, LectureHpTask::getLastErrorCode,
                        LectureHpTask::getLastFailedAt)
                .containsExactlyInAnyOrder(
                        tuple(quarantined.getId(), LectureHpTaskStatus.DEAD, 0, 2, "PAYOUT_ALREADY_SETTLED", FAILED_AT),
                        tuple(failedOnce.getId(), LectureHpTaskStatus.PENDING, 0, 1, "WALLET_NOT_FOUND", FAILED_AT));
    }

    @Test
    void 옮긴_작업은_앱이_같은_요청으로_만든_작업과_요청_값이_같다() throws IOException {
        OrderItem item = confirmedItem(20L);
        recordLegacyFailure(item, 1, "WALLET_NOT_FOUND", false);
        entityManager.clear();

        runMigration();

        LectureHpTask migrated = lectureHpTaskRepository.findByRequestId("LECTURE-PAYOUT-" + item.getId())
                .orElseThrow();
        // 관리자 재시도·재등록 때 지문이 다르면 같은 요청 ID 충돌로 실패한다 (D57 (2))
        LectureHpTask sameRequest = LectureHpTask.create(LectureHpTaskPurpose.PAYOUT, migrated.getRequestId(),
                item.getOrder().getId(), item.getId(), BUYER_ID, SELLER_ID, PRICE_HP, FAILED_AT);
        assertThat(migrated.hasSamePayload(sameRequest)).isTrue();
        assertThat(migrated.getReceiverUserId()).isEqualTo(SELLER_ID);
        assertThat(migrated.getAmountHp()).isEqualTo(PRICE_HP);
    }

    @Test
    void 다시_실행해도_작업이_더_생기지_않는다() throws IOException {
        recordLegacyFailure(confirmedItem(20L), 1, "WALLET_NOT_FOUND", false);
        entityManager.clear();

        runMigration();
        runMigration();

        assertThat(lectureHpTaskRepository.count()).isEqualTo(1);
    }

    private OrderItem confirmedItem(Long lectureId) {
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(withId(onSaleLecture(), lectureId)), PURCHASED_AT));
        OrderItem item = order.getItems().getFirst();
        order.confirmItem(item.getId(), PURCHASED_AT);
        lectureOrderRepository.flush();
        return item;
    }

    /** 엔티티는 더 이상 실패 기록 컬럼을 매핑하지 않으므로 옛 데이터를 SQL로 넣는다 */
    private void recordLegacyFailure(OrderItem item, int count, String code, boolean quarantined) {
        entityManager.createNativeQuery("update lectures.order_item set payout_failure_count = :count, "
                        + "last_payout_failure_code = :code, last_payout_failed_at = :failedAt where id = :id")
                .setParameter("count", count)
                .setParameter("code", code)
                .setParameter("failedAt", FAILED_AT)
                .setParameter("id", item.getId())
                .executeUpdate();
        if (quarantined) {
            entityManager.createNativeQuery("update lectures.order_item set payout_quarantined_at = :quarantinedAt "
                            + "where id = :id")
                    .setParameter("quarantinedAt", FAILED_AT)
                    .setParameter("id", item.getId())
                    .executeUpdate();
        }
    }

    private void runMigration() throws IOException {
        String script = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = Arrays.stream(script.split("\n"))
                .filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : withoutComments.split(";")) {
            if (!statement.isBlank()) {
                entityManager.createNativeQuery(statement).executeUpdate();
            }
        }
        entityManager.clear();
    }

    private OrderItemStatus statusOf(OrderItem item) {
        return OrderItemStatus.valueOf((String) entityManager.createNativeQuery(
                "select status from lectures.order_item where id = :id").setParameter("id", item.getId())
                .getSingleResult());
    }
}
