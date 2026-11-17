package com.hapbang.chat.out;

import static com.hapbang.chat.out.ChatQuerydslSupport.literal;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.port.ChatRoomMemberRepositoryCustom;
import com.hapbang.chat.app.port.ChatRoomMemberRows.MemberWithUser;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;
import com.hapbang.chat.domain.QChatRoom;
import com.hapbang.chat.domain.QChatRoomMember;
import com.hapbang.chat.domain.QChatUser;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatRoomMemberRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatRoomMemberRepository}의 조각으로 붙인다 */
class ChatRoomMemberRepositoryCustomImpl implements ChatRoomMemberRepositoryCustom {

    private static final QChatRoomMember member = QChatRoomMember.chatRoomMember;
    private static final QChatRoom room = QChatRoom.chatRoom;
    private static final QChatUser user = QChatUser.chatUser;

    private final JPAQueryFactory queryFactory;

    ChatRoomMemberRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<ChatRoomMember> findActiveByUserId(Long userId, LocalDateTime now) {
        return queryFactory.selectFrom(member)
                .join(member.chatRoom, room).fetchJoin()
                .where(member.userId.eq(userId), member.leftAt.isNull(), member.deletedAt.isNull(),
                        room.deletedAt.isNull(), viewableRoom(now))
                .orderBy(room.id.desc())
                .fetch();
    }

    /** 1:1 방은 사용 중, 단체방은 사용 중·일시 중지이거나 조회 만료 전의 읽기 전용 */
    private static BooleanExpression viewableRoom(LocalDateTime now) {
        return room.status.eq(literal(ChatRoomStatus.ACTIVE))
                .or(room.roomType.eq(literal(ChatRoomType.GROUP))
                        .and(room.status.eq(literal(ChatRoomStatus.SUSPENDED))
                                .or(room.status.eq(literal(ChatRoomStatus.READ_ONLY))
                                        .and(room.historyExpiresAt.gt(now)))));
    }

    @Override
    public List<ChatRoomMember> findActiveGroupMemberships(Long recruitId, Long userId) {
        return queryFactory.selectFrom(member)
                .join(member.chatRoom, room).fetchJoin()
                .where(room.recruitId.eq(recruitId), room.roomType.eq(literal(ChatRoomType.GROUP)),
                        room.deletedAt.isNull(),
                        member.userId.eq(userId), member.leftAt.isNull(), member.deletedAt.isNull())
                .orderBy(room.id.desc())
                .fetch();
    }

    @Override
    public List<ChatRoomMember> findActivePage(Long chatRoomId, long afterId, Limit limit) {
        return ChatQuerydslSupport.limit(queryFactory.selectFrom(member)
                        .where(activeIn(chatRoomId), member.id.gt(afterId))
                        .orderBy(member.id.asc()), limit)
                .fetch();
    }

    @Override
    public List<Long> findActiveUserIds(Long chatRoomId) {
        return queryFactory.select(member.userId)
                .from(member)
                .where(activeIn(chatRoomId))
                .fetch();
    }

    @Override
    public long countActiveMembers(Long chatRoomId) {
        Long count = queryFactory.select(member.count())
                .from(member)
                .where(activeIn(chatRoomId))
                .fetchOne();
        return count;
    }

    @Override
    public List<MemberWithUser> findActiveWithUser(Long roomId) {
        return queryFactory.select(user, member)
                .from(member)
                .join(user).on(user.id.eq(member.userId))
                .where(activeIn(roomId))
                .fetch().stream()
                .map(row -> new MemberWithUser(row.get(user), row.get(member)))
                .toList();
    }

    /** 방의 지금 참여 중인 행(퇴장·삭제되지 않음) */
    private static BooleanExpression activeIn(Long chatRoomId) {
        return member.chatRoom.id.eq(chatRoomId).and(member.leftAt.isNull()).and(member.deletedAt.isNull());
    }
}
