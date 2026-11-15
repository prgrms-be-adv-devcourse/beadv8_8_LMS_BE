package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.shared.member.event.UserMaskedEvent;

/**
 * 탈퇴 2단계: 마스킹한 회원의 데이터를 지우고 다시 받지 않는다 (#717).
 * <p>
 * {@link UserMaskedEvent}를 받으면 한 트랜잭션에서 회원 락을 잡고 다음을 한다. 같은 이벤트를 여러 번 받아도 결과가 같다.
 * <ul>
 *     <li>{@code masked_member}에 넣는다. 마스킹이 다른 이벤트보다 먼저 와도 이것만 넣고 끝난다(사본이 없어도 된다).</li>
 *     <li>그 회원의 구매·장바구니·쓴 강의 리뷰·받은 출연 리뷰({@link MaskedMemberStore.OwnedCopy})를 지우고, 회원 사본의 분야·자기소개를 비운다.</li>
 *     <li>처음 마스킹할 때 인플루언서 추천 index의 그 회원 문서 삭제를 {@code index_task}에 적는다(실행기는 #719).</li>
 * </ul>
 * 남기는 것: 출연 행(상대방 인기순의 건수, ID만). 그 회원은 보임 판단({@link MemberVisibility})에서 빠진다.
 * 이후 그 회원의 데이터는 적용기가 버린다({@link CopyApplier}).
 * <p>
 * <b>알려진 한계</b>: 마스킹은 이 이벤트로만 안다. 토픽 보존(72시간) 밖에서 놓친 마스킹, 특히 이 앱 첫 배포 전에 마스킹된 회원은 알 수 없다.
 * 그 회원은 탈퇴 상태라 계속 숨겨지지만 구매·장바구니·리뷰 행은 남는다.
 */
@Service
public class MemberMasking {

    private final MaskedMemberStore store;
    private final IndexTaskStore indexTaskStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MemberMasking(MaskedMemberStore store, IndexTaskStore indexTaskStore,
            PlatformTransactionManager transactionManager, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.store = store;
        this.indexTaskStore = indexTaskStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 마스킹을 반영한다 */
    public Result mask(UserMaskedEvent event) {
        long userId = event.userId();
        LocalDateTime maskedAt = event.maskedAt() == null ? CopyTimes.now(clock) : CopyTimes.kst(event.maskedAt());
        return transaction.execute(status -> {
            store.lock(userId);
            boolean first = store.mask(userId, maskedAt);
            MaskedMemberStore.Purged purged = store.purge(userId);
            if (first) {
                indexTaskStore.add(IndexType.INFLUENCER, String.valueOf(userId), IndexAction.DELETE, CopyTimes.now(clock));
            }
            return new Result(first, purged);
        });
    }

    /**
     * @param first  처음 마스킹했는지(다시 받은 이벤트면 false)
     * @param purged 지운 행 수
     */
    public record Result(boolean first, MaskedMemberStore.Purged purged) {
    }
}
