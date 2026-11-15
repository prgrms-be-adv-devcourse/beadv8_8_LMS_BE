package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.cartItem;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.lecture;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.lectureReview;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.member;
import static com.hapbang.auxiliary.chatbot.support.CopyFixtures.purchase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.SyncMismatch;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatchKind;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.SyncMismatchRepository;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;

/** 회원·강의 싱크 5종을 가짜 원본과 실제 DB로 검증한다 (#717) */
class MemberLectureSyncTest extends ChatbotIntegrationTestBase {

    @Autowired
    private ChatbotSyncUseCase chatbotSyncUseCase;

    @Autowired
    private CopyApplier copyApplier;

    @Autowired
    private SyncMismatchRepository syncMismatchRepository;

    @Test
    @DisplayName("회원 싱크: 넣고 고치고, 원본에 없어도 원본 없음을 표시하지 않는다(회원은 하드 삭제가 없다)")
    void members() {
        memberSource.put(member(1, 0L), member(2, 0L));
        assertThat(run(SyncType.MEMBER).getInserted()).isEqualTo(2);

        // 이벤트로만 들어온 회원 3은 원본 목록에 없어도 그대로 둔다
        copyApplier.apply(member(3, 0L));
        memberSource.put(member(1, 1L));
        clock.advance(Duration.ofDays(7));

        SyncRun second = run(SyncType.MEMBER);

        assertThat(second.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(second.getMarkedMissing()).isZero();
        assertThat(second.getFixed()).isZero();
        assertThat(rows.row("member_copy", "id", 1)).containsEntry("version", 1L);
        assertThat(rows.count("member_copy")).isEqualTo(3);
    }

    @Test
    @DisplayName("강의 4종 싱크: 원본에만 있음·원본 새것·우리 새것을 기록하고, 원본에 없는 행은 원본 없음으로 표시한다")
    void lectureFamily() {
        lectureSource.putLectures(lecture(1, 0), lecture(2, 0));
        lectureSource.putPurchases(purchase(10, 5, LecturePurchaseResult.PENDING, 0));
        lectureSource.putCartItems(cartItem(20, 5, false, 0));
        lectureSource.putReviews(lectureReview(30, 5, 4, 0));
        for (SyncType type : new SyncType[] {SyncType.LECTURE, SyncType.LECTURE_PURCHASE, SyncType.CART_ITEM,
                SyncType.LECTURE_REVIEW}) {
            assertThat(run(type).getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        }
        assertThat(rows.count("lecture_copy")).isEqualTo(2);
        assertThat(rows.count("lecture_purchase_copy")).isEqualTo(1);
        assertThat(rows.count("cart_item_copy")).isEqualTo(1);
        assertThat(rows.count("lecture_review_copy")).isEqualTo(1);

        // 원본: 강의 1 새것, 강의 2 사라짐, 강의 3 새로 생김. 우리: 강의 1보다 이벤트로 먼저 받은 구매 version 5
        lectureSource.putLectures(lecture(1, 1), lecture(3, 0));
        lectureSource.removeLecture(2);
        copyApplier.apply(purchase(10, 5, LecturePurchaseResult.OWNED, 5), null);
        clock.advance(Duration.ofDays(7));

        SyncRun lectures = run(SyncType.LECTURE);
        SyncRun purchases = run(SyncType.LECTURE_PURCHASE);

        assertThat(lectures.getInserted()).isEqualTo(1);
        assertThat(lectures.getFixed()).isZero(); // lecture(1, 1)은 값이 같아 version만 오른 정상 건
        assertThat(lectures.getMarkedMissing()).isEqualTo(1);
        assertThat(rows.missing("lecture_copy", "id")).containsExactly(2L);
        assertThat(syncMismatchRepository.findByRunIdOrderByIdAsc(lectures.getId()))
                .extracting(SyncMismatch::getSourceId, SyncMismatch::getKind)
                .containsExactlyInAnyOrder(tuple(3L, SyncMismatchKind.ONLY_IN_SOURCE),
                        tuple(2L, SyncMismatchKind.MISSING_IN_SOURCE));
        assertThat(purchases.getOursNewer()).isEqualTo(1);
        assertThat(rows.row("lecture_purchase_copy", "order_item_id", 10)).containsEntry("result", "OWNED")
                .containsEntry("version", 5L);
    }

    @Test
    @DisplayName("경고 비율은 버린 건(마스킹)을 빼고 계산하고, 버린 건은 회차에 discarded로만 센다")
    void discardedExcludedFromWarning() {
        // 첫 실행(성공 회차 만들기)
        lectureSource.putPurchases(purchase(1, 5, LecturePurchaseResult.OWNED, 0));
        run(SyncType.LECTURE_PURCHASE);

        // 마스킹한 회원 99의 구매 20건 + 정상 구매 30건(그중 이벤트로 이미 받은 20건, 새로 생긴 10건)
        maskedMember(99);
        lectureSource.putPurchases(LongStream.rangeClosed(100, 119)
                .mapToObj(id -> purchase(id, 99, LecturePurchaseResult.OWNED, 0))
                .toArray(com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto[]::new));
        lectureSource.putPurchases(LongStream.rangeClosed(200, 229)
                .mapToObj(id -> purchase(id, 5, LecturePurchaseResult.OWNED, 0))
                .toArray(com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto[]::new));
        LongStream.rangeClosed(200, 219).forEach(id ->
                copyApplier.apply(purchase(id, 5, LecturePurchaseResult.OWNED, 0), null));
        clock.advance(Duration.ofDays(7));

        SyncRun run = run(SyncType.LECTURE_PURCHASE);

        // 읽은 51건 = 첫 구매 1 + 버림 20 + 이미 있음 20 + 새로 10. 비율 10 / 31 ≥ 0.3 (버린 건을 넣으면 10 / 51 < 0.3)
        assertThat(run.getScanned()).isEqualTo(51);
        assertThat(run.getDiscarded()).isEqualTo(20);
        assertThat(run.getInserted()).isEqualTo(10);
        assertThat(run.isWarning()).isTrue();
        assertThat(syncMismatchRepository.findByRunIdOrderByIdAsc(run.getId()))
                .extracting(SyncMismatch::getSourceId).allMatch(id -> id >= 200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chatbot.lecture_purchase_copy WHERE buyer_user_id = 99", Integer.class)).isZero();
    }

    private void maskedMember(long userId) {
        jdbcTemplate.update("INSERT INTO chatbot.masked_member (user_id, masked_at) VALUES (?, now())", userId);
    }

    private SyncRun run(SyncType type) {
        return chatbotSyncUseCase.runBatch(type, SyncTrigger.SCHEDULED);
    }
}
