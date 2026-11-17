package com.hapbang.chat.out;

import static com.hapbang.chat.out.ChatQuerydslSupport.after;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.ChatMessageRepositoryCustom;
import com.hapbang.chat.app.ChatRoomUnreadCount;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.QChatMessage;
import com.hapbang.chat.domain.QChatRoomMember;
import com.hapbang.chat.domain.QChatUser;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatMessageRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatMessageRepository}의 조각으로 붙인다 */
class ChatMessageRepositoryCustomImpl implements ChatMessageRepositoryCustom {

    private static final QChatMessage message = QChatMessage.chatMessage;
    private static final QChatRoomMember member = QChatRoomMember.chatRoomMember;
    private static final QChatUser sender = QChatUser.chatUser;

    private final JPAQueryFactory queryFactory;

    ChatMessageRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<Object[]> findWithSender(long messageId) {
        return queryFactory.select(message, sender)
                .from(message)
                .leftJoin(sender).on(sender.id.eq(message.senderUserId))
                .where(message.id.eq(messageId))
                .fetch().stream()
                .map(Tuple::toArray)
                .toList();
    }

    @Override
    public List<ChatMessage> findVisible(Long chatRoomId, long fromSequence, long beforeSequence, Limit limit) {
        return ChatQuerydslSupport.limit(queryFactory.selectFrom(message)
                        .where(message.chatRoom.id.eq(chatRoomId),
                                message.roomSequence.goe(fromSequence), message.roomSequence.lt(beforeSequence),
                                message.deletedAt.isNull())
                        .orderBy(message.roomSequence.desc()), limit)
                .fetch();
    }

    @Override
    public List<ChatMessage> findLastVisible(Long userId, Collection<Long> chatRoomIds) {
        QChatMessage latest = new QChatMessage("latest");
        return queryFactory.select(message)
                .from(message, member)
                .where(member.chatRoom.id.eq(message.chatRoom.id), member.userId.eq(userId),
                        member.deletedAt.isNull(),
                        message.chatRoom.id.in(chatRoomIds),
                        message.roomSequence.eq(JPAExpressions.select(latest.roomSequence.max())
                                .from(latest)
                                .where(latest.chatRoom.id.eq(message.chatRoom.id), latest.deletedAt.isNull(),
                                        latest.roomSequence.goe(member.visibleFromSequence))))
                .fetch();
    }

    @Override
    public List<ChatRoomUnreadCount> countUnread(Long userId, Collection<Long> chatRoomIds) {
        return queryFactory.select(Projections.constructor(ChatRoomUnreadCount.class,
                        message.chatRoom.id, message.count()))
                .from(message, member)
                .where(member.chatRoom.id.eq(message.chatRoom.id), member.userId.eq(userId),
                        member.deletedAt.isNull(),
                        message.chatRoom.id.in(chatRoomIds),
                        message.roomSequence.goe(member.visibleFromSequence),
                        message.roomSequence.gt(member.lastReadSequence),
                        message.senderUserId.ne(userId), message.deletedAt.isNull())
                .groupBy(message.chatRoom.id)
                .fetch();
    }

    @Override
    public List<ChatMessage> findHistoryAfter(Long chatRoomId, LocalDateTime at, long idAfter, Limit limit) {
        return ChatQuerydslSupport.limit(queryFactory.selectFrom(message)
                        .where(message.chatRoom.id.eq(chatRoomId), after(message.sentAt, message.id, at, idAfter))
                        .orderBy(message.sentAt.asc(), message.id.asc()), limit)
                .fetch();
    }
}
