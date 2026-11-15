package com.hapbang.auxiliary.chatbot.app;

import com.hapbang.auxiliary.global.scheduling.JobLockKey;

/**
 * 한 번에 한 곳에서만 실행하는 챗봇 작업과 그 advisory lock 번호 (#716, #715, #717).
 * <p>
 * auxiliary database를 검색·알림 모듈과 함께 쓰므로 번호가 모듈 사이에서도 겹치면 안 된다. 그 작업을 더한 이슈 번호 × 1000 + 순번을 쓰고,
 * 한 번 정한 번호는 바꾸지 않는다({@link JobLockKey}). enum 안에서 겹치지 않는지는 테스트가 본다.
 */
public enum ChatbotJob implements JobLockKey {

    /** #716 */
    APPEARANCE_SYNC(716_001L, "chatbot-appearance-sync", "출연 싱크"),
    /** #716 */
    APPEARANCE_REVIEW_SYNC(716_002L, "chatbot-appearance-review-sync", "공개된 출연 리뷰 싱크"),
    /** #715 */
    FAQ_SYNC(715_001L, "chatbot-faq-sync", "FAQ 시드·벡터 색인 맞추기(기동 시·주기 점검)"),
    /** #717 */
    MEMBER_SYNC(717_001L, "chatbot-member-sync", "회원 싱크"),
    /** #717 */
    LECTURE_SYNC(717_002L, "chatbot-lecture-sync", "강의 싱크"),
    /** #717 */
    LECTURE_PURCHASE_SYNC(717_003L, "chatbot-lecture-purchase-sync", "구매 싱크"),
    /** #717 */
    CART_ITEM_SYNC(717_004L, "chatbot-cart-item-sync", "장바구니 싱크"),
    /** #717 */
    LECTURE_REVIEW_SYNC(717_005L, "chatbot-lecture-review-sync", "강의 리뷰 싱크"),
    /**
     * #718. 다른 작업과 달리 {@code JobLock}(세션 락)이 아니라 가져가기 트랜잭션 안에서
     * {@code pg_advisory_xact_lock}으로 잠깐 잡는다. 여러 pod의 가져가기를 한 줄로 세워 같은 문서를 두 pod가 함께 가져가지 않게 한다
     */
    INDEX_TASK_CLAIM(718_001L, "chatbot-index-task-claim", "색인 요청 가져가기");

    private final long lockKey;
    private final String tag;
    private final String description;

    ChatbotJob(long lockKey, String tag, String description) {
        this.lockKey = lockKey;
        this.tag = tag;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public long lockKey() {
        return lockKey;
    }

    @Override
    public String tag() {
        return tag;
    }
}
