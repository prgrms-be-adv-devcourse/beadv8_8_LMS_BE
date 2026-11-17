package com.hapbang.chat.out;

import static com.hapbang.chat.out.ChatQuerydslSupport.literal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.port.ChatRoomRepositoryCustom;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;
import com.hapbang.chat.domain.QChatRecipientSet;
import com.hapbang.chat.domain.QChatRoom;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatRoomRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatRoomRepository}의 조각으로 붙인다 */
class ChatRoomRepositoryCustomImpl implements ChatRoomRepositoryCustom {

    private static final QChatRoom room = QChatRoom.chatRoom;
    private static final QChatRecipientSet recipientSet = QChatRecipientSet.chatRecipientSet;

    private final JPAQueryFactory queryFactory;

    ChatRoomRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<ChatRoom> findCurrentGroup(Long recruitId) {
        return Optional.ofNullable(queryFactory.selectFrom(room)
                .where(room.recruitId.eq(recruitId), room.roomType.eq(literal(ChatRoomType.GROUP)),
                        room.status.in(literal(ChatRoomStatus.ACTIVE), literal(ChatRoomStatus.SUSPENDED)),
                        room.deletedAt.isNull())
                .fetchOne());
    }

    @Override
    public List<Long> findHistoryExpiryDueIds(LocalDateTime now, long afterId, Limit limit) {
        return ChatQuerydslSupport.limit(queryFactory.select(room.id)
                        .from(room)
                        .where(room.roomType.eq(literal(ChatRoomType.GROUP)),
                                room.status.eq(literal(ChatRoomStatus.READ_ONLY)),
                                room.historyExpiresAt.loe(now), room.id.gt(afterId), room.deletedAt.isNull())
                        .orderBy(room.id.asc()), limit)
                .fetch();
    }

    @Override
    public Optional<Integer> findCurrentRecipientSetVersion(Long roomId) {
        return queryFactory.select(recipientSet.setVersion)
                .from(room, recipientSet)
                .where(room.id.eq(roomId), recipientSet.id.eq(room.currentRecipientSetId))
                .fetch().stream()
                .findFirst();
    }
}
