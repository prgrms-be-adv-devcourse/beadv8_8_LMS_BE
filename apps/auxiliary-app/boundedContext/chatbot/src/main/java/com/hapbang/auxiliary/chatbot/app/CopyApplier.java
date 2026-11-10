package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.CopyOutcome;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureCopies;
import com.hapbang.auxiliary.chatbot.domain.MemberCopy;
import com.hapbang.auxiliary.chatbot.out.AppearanceCopyStore;
import com.hapbang.auxiliary.chatbot.out.AppearanceReviewCopyStore;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.LectureCopyStore;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.auxiliary.chatbot.out.MemberCopyStore;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 받은 값을 읽기용 사본에 반영하는 적용기 (#716, #717). Kafka 이벤트와 매시 싱크가 같은 메서드를 부른다.
 * <ul>
 *     <li>출연·강의 이벤트와 싱크는 같은 DTO라 그대로 넘긴다. 리뷰 공개 이벤트는 싱크 DTO의 4필드만 뽑는다.</li>
 *     <li>순서 보장이 없다. 리뷰·구매가 출연·강의·회원보다 먼저 와도 그대로 넣는다(사본 사이에 FK가 없다).</li>
 *     <li>회원 변경 이벤트도 사본이 없으면 만든다(다른 앱의 회원 복제본 규칙과 다름, docs/dev/module-integration.md 읽기용 사본 예외).
 *     version으로 순서를 정하고 매시 싱크가 같은 행을 만들므로 결과가 같다.</li>
 *     <li><b>마스킹한 회원의 데이터</b>(구매·장바구니·쓴 강의 리뷰·받은 출연 리뷰·분야·자기소개)는 반영하지 않는다({@link CopyOutcome#DISCARDED}).
 *     회원 행은 역할·상태만 반영하고 결과는 {@link CopyOutcome#DISCARDED}로 센다.
 *     회원마다 트랜잭션 advisory lock을 잡고 마스킹 여부를 본 뒤 쓴다. 마스킹({@link MemberMasking})도 같은 락을 잡으므로,
 *     마스킹 직후 커밋 전의 행이 다시 들어오지 않는다.</li>
 *     <li>출연 사본은 upsert 한 문장이라 트랜잭션을 따로 열지 않는다.</li>
 *     <li><b>강의 사본</b>은 값이 바뀌었거나 원본 없음 표시가 바뀌면 같은 트랜잭션에서 강의 색인 요청({@code index_task})을 적는다(#718).
 *     커밋 뒤 실행기({@link IndexTaskRunner})가 처리한다. 값이 같은 싱크 반영은 요청을 적지 않는다(매시 전체 재색인 방지).
 *     회원 사본도 같은 방식으로 인플루언서 색인 요청을 적는다(#719).</li>
 * </ul>
 */
@Service
public class CopyApplier {

    private final AppearanceCopyStore appearanceCopyStore;
    private final AppearanceReviewCopyStore appearanceReviewCopyStore;
    private final MemberCopyStore memberCopyStore;
    private final LectureCopyStore lectureCopyStore;
    private final MaskedMemberStore maskedMemberStore;
    private final IndexTaskStore indexTaskStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CopyApplier(AppearanceCopyStore appearanceCopyStore, AppearanceReviewCopyStore appearanceReviewCopyStore,
            MemberCopyStore memberCopyStore, LectureCopyStore lectureCopyStore, MaskedMemberStore maskedMemberStore,
            IndexTaskStore indexTaskStore, PlatformTransactionManager transactionManager,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.appearanceCopyStore = appearanceCopyStore;
        this.appearanceReviewCopyStore = appearanceReviewCopyStore;
        this.memberCopyStore = memberCopyStore;
        this.lectureCopyStore = lectureCopyStore;
        this.maskedMemberStore = maskedMemberStore;
        this.indexTaskStore = indexTaskStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 출연을 반영한다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite apply(AppearanceSyncDto appearance, Long runId) {
        return appearanceCopyStore.save(AppearanceCopy.from(appearance), runId, now());
    }

    /**
     * 공개된 출연 리뷰를 반영한다. 리뷰를 받은 회원을 마스킹했으면 버린다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite apply(AppearanceReviewSyncDto review, Long runId) {
        AppearanceReviewCopy copy = AppearanceReviewCopy.from(review);
        return unlessMasked(copy.revieweeUserId(), () -> appearanceReviewCopyStore.save(copy, runId, now()));
    }

    /** 리뷰 공개 이벤트를 반영한다 */
    public CopyWrite apply(AppearanceReviewPublishedEvent event) {
        AppearanceReviewCopy copy = AppearanceReviewCopy.from(event);
        return unlessMasked(copy.revieweeUserId(), () -> appearanceReviewCopyStore.save(copy, null, now()));
    }

    /**
     * 회원을 반영한다(가입·변경 이벤트, 회원 싱크). 사본이 없으면 만든다. 회원 사본은 원본 없음을 표시하지 않아 싱크 회차 ID를 받지 않는다.
     * <p>
     * 값(역할·상태·분야·자기소개 등)이 바뀌면 같은 트랜잭션에서 인플루언서 색인 요청을 적는다(#719). 역할과 관계없이 적는다:
     * 강등된 회원의 문서도 숨겨야 하고, 문서가 없는 일반 회원은 처리기가 아무것도 하지 않는다. 값이 같은 매시 싱크는 적지 않는다.
     * <p>
     * 마스킹한 회원은 역할·상태만 반영하고 분야·자기소개는 버린다. 색인 삭제 요청은 마스킹({@link MemberMasking})이 이미 적었다. 버린 값이 있는 행이라 결과는 반영 여부와 관계없이
     * {@link CopyOutcome#DISCARDED}로 센다. 그래서 마스킹 회원의 행은 어긋남·경고 비율 집계에서 빠진다(#717 스펙).
     */
    public CopyWrite apply(UserDto user) {
        MemberCopy copy = MemberCopy.from(user);
        return transaction.execute(status -> {
            maskedMemberStore.lock(copy.id());
            if (!maskedMemberStore.isMasked(copy.id())) {
                LocalDateTime now = now();
                CopyWrite write = memberCopyStore.save(copy, now);
                if (write.outcome().valueChanged()) {
                    indexTaskStore.add(IndexType.INFLUENCER, String.valueOf(copy.id()), IndexAction.UPSERT, now);
                }
                return write;
            }
            CopyWrite written = memberCopyStore.save(copy.withoutProfile(), now());
            return new CopyWrite(CopyOutcome.DISCARDED, written.ourVersion());
        });
    }

    /**
     * 강의를 반영한다. 값이 바뀌었거나 원본 없음 표시를 풀었으면 같은 트랜잭션에서 강의 색인 요청을 적는다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite apply(LectureSyncDto lecture, Long runId) {
        LectureCopies.Lecture copy = LectureCopies.Lecture.from(lecture);
        return transaction.execute(status -> {
            LocalDateTime now = now();
            CopyWrite write = lectureCopyStore.save(copy, runId, now);
            if (write.outcome().valueChanged() || write.restored()) {
                indexTaskStore.add(IndexType.LECTURE, String.valueOf(copy.id()), IndexAction.UPSERT, now);
            }
            return write;
        });
    }

    /**
     * 끝까지 성공한 강의 싱크 회차의 끝에 원본에 없던 강의를 표시하고, 같은 트랜잭션에서 그 강의들의 색인 요청을 적는다(추천에서 뺀다).
     *
     * @return 새로 표시한 행
     */
    public List<MissingCopy> markMissingLectures(long runId, LocalDateTime startedAt) {
        return transaction.execute(status -> {
            List<MissingCopy> missing = lectureCopyStore.markMissingLectures(runId, startedAt);
            indexTaskStore.addAll(IndexType.LECTURE, missing.stream().map(row -> String.valueOf(row.sourceId())).toList(),
                    IndexAction.UPSERT, now());
            return missing;
        });
    }

    /** 구매를 반영한다. 구매자를 마스킹했으면 버린다. @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite apply(LecturePurchaseSyncDto purchase, Long runId) {
        LectureCopies.Purchase copy = LectureCopies.Purchase.from(purchase);
        return unlessMasked(copy.buyerUserId(), () -> lectureCopyStore.save(copy, runId, now()));
    }

    /** 장바구니 항목을 반영한다. 주인을 마스킹했으면 버린다. @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite apply(CartItemSyncDto cartItem, Long runId) {
        LectureCopies.CartItem copy = LectureCopies.CartItem.from(cartItem);
        return unlessMasked(copy.userId(), () -> lectureCopyStore.save(copy, runId, now()));
    }

    /** 강의 리뷰를 반영한다. 작성자를 마스킹했으면 버린다. @param runId 싱크 회차 ID. 이벤트면 null */
    public CopyWrite apply(LectureReviewSyncDto review, Long runId) {
        LectureCopies.Review copy = LectureCopies.Review.from(review);
        return unlessMasked(copy.reviewerUserId(), () -> lectureCopyStore.save(copy, runId, now()));
    }

    // 회원 락을 잡고, 마스킹한 회원이면 쓰지 않는다
    private CopyWrite unlessMasked(long userId, Supplier<CopyWrite> write) {
        return transaction.execute(status -> {
            maskedMemberStore.lock(userId);
            if (maskedMemberStore.isMasked(userId)) {
                return new CopyWrite(CopyOutcome.DISCARDED, null);
            }
            return write.get();
        });
    }

    private LocalDateTime now() {
        return CopyTimes.now(clock);
    }
}
