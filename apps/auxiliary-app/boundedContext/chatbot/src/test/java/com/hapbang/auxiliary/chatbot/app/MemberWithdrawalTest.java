package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.cartItem;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.lectureReview;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.member;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.purchase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore.OwnedCopy;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserMaskedEvent;

/** 탈퇴 1단계(숨김)와 2단계(마스킹 삭제 + 재유입 차단) (#717) */
class MemberWithdrawalTest extends ChatbotIntegrationTestBase {

    private static final long USER = 5L;
    private static final long OTHER = 6L;
    private static final Instant MASKED_AT = Instant.parse("2026-11-09T01:00:00Z");

    @Autowired
    private CopyApplier copyApplier;

    @Autowired
    private MemberMasking memberMasking;

    @Autowired
    private MemberVisibility memberVisibility;

    @Autowired
    private ChatbotSyncUseCase chatbotSyncUseCase;

    @Test
    @DisplayName("숨김: 탈퇴하면 숨기고 복구하면 다시 보인다. 사본이 아직 없으면 숨긴다. 데이터는 지우지 않는다")
    void visibility() {
        assertThat(memberVisibility.isVisible(USER)).isFalse();
        copyApplier.apply(member(USER, 0L));
        copyApplier.apply(purchase(1, USER, LecturePurchaseResult.OWNED, 0), null);
        assertThat(memberVisibility.isVisible(USER)).isTrue();

        copyApplier.apply(member(USER, Role.INFLUENCER, UserStatus.WITHDRAWN, 1L));
        assertThat(memberVisibility.isVisible(USER)).isFalse();
        assertThat(rows.count("lecture_purchase_copy")).isEqualTo(1);

        copyApplier.apply(member(USER, Role.INFLUENCER, UserStatus.ACTIVE, 2L));
        assertThat(memberVisibility.visibleOf(List.of(USER, OTHER, 7L))).containsExactly(USER);
    }

    @Test
    @DisplayName("마스킹: 구매·장바구니·쓴 강의 리뷰·받은 출연 리뷰를 지우고 분야·자기소개를 비우고, 인플루언서 문서 삭제를 한 번만 적는다")
    void maskPurges() {
        givenUserData();

        MemberMasking.Result result = memberMasking.mask(new UserMaskedEvent(USER, MASKED_AT));

        assertThat(result.first()).isTrue();
        assertThat(result.purged().deleted()).containsOnlyKeys(OwnedCopy.values()).values().containsOnly(1);
        assertThat(result.purged().profileCleared()).isTrue();
        assertThat(rows.maskedMembers()).containsExactly(USER);
        assertThat(rows.row("masked_member", "user_id", USER).get("masked_at").toString())
                .startsWith("2026-11-09 10:00:00");
        assertThat(rows.row("member_copy", "id", USER)).containsEntry("bio", null).containsEntry("status", "WITHDRAWN");
        assertThat(rows.memberCategories(USER)).isEqualTo("[]");
        // 다른 회원의 데이터와 출연 행(ID만)은 남긴다
        assertThat(rows.count("lecture_purchase_copy")).isEqualTo(1);
        assertThat(rows.count("cart_item_copy")).isZero();
        assertThat(rows.count("lecture_review_copy")).isEqualTo(1);
        assertThat(rows.count("appearance_review_copy")).isEqualTo(1);
        assertThat(memberVisibility.isVisible(USER)).isFalse();
        // 회원 반영 때 적은 UPSERT(#719) 뒤에 삭제 요청이 하나 붙는다
        assertThat(deleteTasks()).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("index_type", "INFLUENCER").containsEntry("doc_id", String.valueOf(USER))
                .containsEntry("done_at", null));

        // 같은 이벤트를 다시 받아도 결과가 같다(문서 삭제도 다시 적지 않는다)
        MemberMasking.Result again = memberMasking.mask(new UserMaskedEvent(USER, MASKED_AT));
        assertThat(again.first()).isFalse();
        assertThat(again.purged().deleted()).containsOnlyKeys(OwnedCopy.values()).values().containsOnly(0);
        assertThat(again.purged().profileCleared()).isFalse();
        assertThat(deleteTasks()).hasSize(1);
    }

    private List<Map<String, Object>> deleteTasks() {
        return rows.indexTasks().stream().filter(task -> "DELETE".equals(task.get("action"))).toList();
    }

    @Test
    @DisplayName("마스킹 뒤 같은 이벤트를 다시 받아도 되살아나지 않고 버린다. 회원 변경은 역할·상태만 반영한다")
    void eventsAfterMaskDiscarded() {
        memberMasking.mask(new UserMaskedEvent(USER, MASKED_AT));

        assertThat(copyApplier.apply(purchase(1, USER, LecturePurchaseResult.OWNED, 9), null).outcome())
                .isEqualTo(CopyOutcome.DISCARDED);
        assertThat(copyApplier.apply(cartItem(2, USER, false, 9), null).outcome()).isEqualTo(CopyOutcome.DISCARDED);
        assertThat(copyApplier.apply(lectureReview(3, USER, 5, 9), null).outcome()).isEqualTo(CopyOutcome.DISCARDED);
        assertThat(copyApplier.apply(new AppearanceReviewSyncDto(4L, 40L, USER, 5), null).outcome())
                .isEqualTo(CopyOutcome.DISCARDED);
        // 마스킹이 먼저 왔어도 회원 사본은 만든다. 분야·자기소개는 버리고 결과는 버림으로 센다
        assertThat(copyApplier.apply(member(USER, Role.INFLUENCER, UserStatus.WITHDRAWN, 9L)).outcome())
                .isEqualTo(CopyOutcome.DISCARDED);

        assertThat(rows.count("lecture_purchase_copy") + rows.count("cart_item_copy") + rows.count("lecture_review_copy")
                + rows.count("appearance_review_copy")).isZero();
        assertThat(rows.row("member_copy", "id", USER)).containsEntry("status", "WITHDRAWN").containsEntry("bio", null);
        assertThat(rows.memberCategories(USER)).isEqualTo("[]");
    }

    @Test
    @DisplayName("마스킹 뒤 싱크를 다시 돌려도 되살아나지 않고, 버린 건은 discarded로만 센다(어긋남·경고 아님)")
    void syncAfterMaskDiscarded() {
        givenUserData();
        lectureSource.putPurchases(purchase(1, USER, LecturePurchaseResult.OWNED, 0),
                purchase(2, OTHER, LecturePurchaseResult.OWNED, 0));
        memberSource.put(member(USER, Role.INFLUENCER, UserStatus.WITHDRAWN, 5L), member(OTHER, 0L));
        memberMasking.mask(new UserMaskedEvent(USER, MASKED_AT));
        clock.advance(Duration.ofDays(1));

        SyncRun purchases = chatbotSyncUseCase.runBatch(SyncType.LECTURE_PURCHASE, SyncTrigger.SCHEDULED);
        SyncRun members = chatbotSyncUseCase.runBatch(SyncType.MEMBER, SyncTrigger.SCHEDULED);

        assertThat(purchases.getScanned()).isEqualTo(2);
        assertThat(purchases.getDiscarded()).isEqualTo(1);
        assertThat(purchases.getInserted()).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT buyer_user_id FROM chatbot.lecture_purchase_copy", Long.class))
                .containsExactly(OTHER);
        assertThat(members.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        // 마스킹 회원의 행은 역할·상태·version만 반영하고 discarded로 센다(고친 건수·경고 비율에 넣지 않는다)
        assertThat(members.getDiscarded()).isEqualTo(1);
        assertThat(members.getFixed()).isZero();
        assertThat(rows.row("member_copy", "id", USER)).containsEntry("bio", null).containsEntry("version", 5L);
    }

    @Test
    @DisplayName("싱크 회차 끝의 정리는 어떤 경로로든 남은 마스킹 회원의 행을 지운다")
    void runEndPurge() {
        givenUserData();
        rows.masked(USER);

        chatbotSyncUseCase.runBatch(SyncType.LECTURE, SyncTrigger.SCHEDULED);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM chatbot.lecture_purchase_copy WHERE buyer_user_id = ?)
                     + (SELECT count(*) FROM chatbot.appearance_review_copy WHERE reviewee_user_id = ?)
                """, Integer.class, USER, USER)).isZero();
        assertThat(rows.row("member_copy", "id", USER)).containsEntry("bio", null);
    }

    @Test
    @DisplayName("마스킹과 그 회원의 강의 이벤트를 동시에 처리해도 되살아나지 않는다(회원 락으로 직렬화)")
    void concurrentMaskAndEvents() throws Exception {
        for (int round = 0; round < 10; round++) {
            long user = 1000 + round;
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    long orderItemId = user * 10 + i;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return copyApplier.apply(purchase(orderItemId, user, LecturePurchaseResult.OWNED, 0), null);
                    }));
                }
                futures.add(executor.submit(() -> {
                    start.await();
                    return memberMasking.mask(new UserMaskedEvent(user, MASKED_AT));
                }));
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM chatbot.lecture_purchase_copy WHERE buyer_user_id = ?", Integer.class, user))
                    .isZero();
        }
    }

    // USER의 구매·장바구니·쓴 리뷰·받은 출연 리뷰·분야·자기소개, OTHER의 구매·쓴 리뷰·받은 출연 리뷰
    private void givenUserData() {
        copyApplier.apply(member(USER, Role.INFLUENCER, UserStatus.WITHDRAWN, 1L));
        copyApplier.apply(purchase(1, USER, LecturePurchaseResult.OWNED, 0), null);
        copyApplier.apply(purchase(2, OTHER, LecturePurchaseResult.OWNED, 0), null);
        copyApplier.apply(cartItem(3, USER, false, 0), null);
        copyApplier.apply(lectureReview(4, USER, 5, 0), null);
        copyApplier.apply(lectureReview(5, OTHER, 3, 0), null);
        copyApplier.apply(new AppearanceReviewSyncDto(6L, 40L, USER, 5), null);
        copyApplier.apply(new AppearanceReviewSyncDto(7L, 40L, OTHER, 4), null);
    }
}
