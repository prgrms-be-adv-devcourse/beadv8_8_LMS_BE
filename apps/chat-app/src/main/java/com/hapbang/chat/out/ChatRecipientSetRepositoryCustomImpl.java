package com.hapbang.chat.out;

import com.hapbang.chat.app.ChatRecipientSetRepositoryCustom;
import com.hapbang.chat.domain.QChatRecipientSet;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatRecipientSetRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatRecipientSetRepository}의 조각으로 붙인다 */
class ChatRecipientSetRepositoryCustomImpl implements ChatRecipientSetRepositoryCustom {

    private static final QChatRecipientSet recipientSet = QChatRecipientSet.chatRecipientSet;

    private final JPAQueryFactory queryFactory;

    ChatRecipientSetRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public int findLastVersion(Long chatRoomId) {
        Integer lastVersion = queryFactory.select(recipientSet.setVersion.max().coalesce(Expressions.ZERO))
                .from(recipientSet)
                .where(recipientSet.chatRoomId.eq(chatRoomId))
                .fetchOne();
        return lastVersion;
    }
}
