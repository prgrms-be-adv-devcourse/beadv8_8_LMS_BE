package com.hapbang.chat.out;

import static com.hapbang.chat.out.ChatQuerydslSupport.after;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.port.ChatRoomLogRepositoryCustom;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.QChatRoomLog;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatRoomLogRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatRoomLogRepository}의 조각으로 붙인다 */
class ChatRoomLogRepositoryCustomImpl implements ChatRoomLogRepositoryCustom {

    private static final QChatRoomLog roomLog = QChatRoomLog.chatRoomLog;

    private final JPAQueryFactory queryFactory;

    ChatRoomLogRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<ChatRoomLog> findHistoryAfter(Long chatRoomId, LocalDateTime at, long idAfter, Limit limit) {
        return ChatQuerydslSupport.limit(queryFactory.selectFrom(roomLog)
                        .where(roomLog.chatRoom.id.eq(chatRoomId),
                                after(roomLog.occurredAt, roomLog.id, at, idAfter))
                        .orderBy(roomLog.occurredAt.asc(), roomLog.id.asc()), limit)
                .fetch();
    }
}
