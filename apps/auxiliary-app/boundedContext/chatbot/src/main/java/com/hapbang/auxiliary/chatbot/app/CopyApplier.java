package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.out.AppearanceCopyStore;
import com.hapbang.auxiliary.chatbot.out.AppearanceReviewCopyStore;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 받은 값을 읽기용 사본에 반영하는 적용기 (#716). Kafka 이벤트와 주간 싱크가 같은 메서드를 부른다.
 * <ul>
 *     <li>출연은 이벤트와 싱크가 같은 {@link AppearanceSyncDto}라 그대로 넘긴다.</li>
 *     <li>출연 리뷰는 공개 이벤트에서 싱크 DTO의 4필드만 뽑아 넘긴다({@link AppearanceReviewCopy#from(AppearanceReviewPublishedEvent)}).</li>
 *     <li>순서 보장이 없다. 리뷰가 출연보다 먼저 와도 그대로 넣는다(사본 사이에 FK가 없다).</li>
 *     <li>반영은 upsert 한 문장이라 트랜잭션을 따로 열지 않는다.</li>
 * </ul>
 */
@Service
public class CopyApplier {

    private final AppearanceCopyStore appearanceCopyStore;
    private final AppearanceReviewCopyStore appearanceReviewCopyStore;
    private final Clock clock;

    public CopyApplier(AppearanceCopyStore appearanceCopyStore, AppearanceReviewCopyStore appearanceReviewCopyStore,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.appearanceCopyStore = appearanceCopyStore;
        this.appearanceReviewCopyStore = appearanceReviewCopyStore;
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
     * 공개된 출연 리뷰를 반영한다.
     *
     * @param runId 싱크 회차 ID. 이벤트면 null
     */
    public CopyWrite apply(AppearanceReviewSyncDto review, Long runId) {
        return appearanceReviewCopyStore.save(AppearanceReviewCopy.from(review), runId, now());
    }

    /** 리뷰 공개 이벤트를 반영한다 */
    public CopyWrite apply(AppearanceReviewPublishedEvent event) {
        return appearanceReviewCopyStore.save(AppearanceReviewCopy.from(event), null, now());
    }

    private LocalDateTime now() {
        return CopyTimes.now(clock);
    }
}
