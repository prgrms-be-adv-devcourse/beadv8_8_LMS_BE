package com.hapbang.chat.out;

import java.util.Optional;

import com.hapbang.chat.app.port.ChatRoomMemberRows.UserWithMembership;
import com.hapbang.chat.app.port.ChatUserRepositoryCustom;
import com.hapbang.chat.domain.QChatRoomMember;
import com.hapbang.chat.domain.QChatUser;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;

/** {@link ChatUserRepositoryCustom}의 QueryDSL 구현. Spring Data가 {@code ChatUserRepository}의 조각으로 붙인다 */
class ChatUserRepositoryCustomImpl implements ChatUserRepositoryCustom {

    private static final QChatUser user = QChatUser.chatUser;
    private static final QChatRoomMember member = QChatRoomMember.chatRoomMember;

    private final JPAQueryFactory queryFactory;

    ChatUserRepositoryCustomImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<UserWithMembership> findWithMembership(Long userId, Long roomId) {
        return queryFactory.select(user, member)
                .from(user)
                .leftJoin(member).on(member.userId.eq(user.id), member.chatRoom.id.eq(roomId),
                        member.deletedAt.isNull())
                .where(user.id.eq(userId))
                .fetch().stream()
                .findFirst()
                .map(row -> new UserWithMembership(row.get(user), row.get(member)));
    }
}
