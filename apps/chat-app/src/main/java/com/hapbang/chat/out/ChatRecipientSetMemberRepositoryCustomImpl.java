package com.hapbang.chat.out;

import java.util.List;

import com.hapbang.chat.domain.QChatRecipientSetMember;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/**
 * {@link ChatRecipientSetMemberRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@link ChatRecipientSetMemberRepository}의
 * 조각으로 붙인다
 */
class ChatRecipientSetMemberRepositoryCustomImpl implements ChatRecipientSetMemberRepositoryCustom {

    private static final QChatRecipientSetMember setMember = QChatRecipientSetMember.chatRecipientSetMember;

    private final JPAQueryFactory queryFactory;

    ChatRecipientSetMemberRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<Long> findUserIds(Long recipientSetId) {
        return queryFactory.select(setMember.userId)
                .from(setMember)
                .where(setMember.recipientSetId.eq(recipientSetId))
                .orderBy(setMember.userId.asc())
                .fetch();
    }
}
