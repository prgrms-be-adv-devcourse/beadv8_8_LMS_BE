package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.ChatRoomMemberRows.MemberWithUser;
import com.hapbang.chat.domain.ChatRoomMember;

/**
 * {@link ChatRoomMemberRepository}의 QueryDSL 조회. 구현은 out의 {@code ChatRoomMemberRepositoryCustomImpl}이다.
 * 쓰기(저장·조건부 UPDATE)와 이름으로 만드는 조회는 {@link ChatRoomMemberRepository}에 둔다.
 */
public interface ChatRoomMemberRepositoryCustom {

    /**
     * 회원이 참여 중인, 볼 수 있는 방의 참여 정보(방을 함께 읽는다, 최근 방부터). 1:1 방은 종료되지 않은 방이고, 단체방은 사용 중·일시
     * 중지 방과 조회 만료 전의 읽기 전용 방이다(만료 시각에 도달하면 나오지 않는다).
     */
    List<ChatRoomMember> findActiveByUserId(Long userId, LocalDateTime now);

    /**
     * 모집글의 단체방 중 회원이 참여 중인 방의 참여 정보(방을 함께 읽는다, 최근 방부터). 조회 만료 여부는 부르는 쪽이 방으로 거른다.
     */
    List<ChatRoomMember> findActiveGroupMemberships(Long recruitId, Long userId);

    /** 방의 활성 참여자 한 페이지. 참여 행 ID 순서의 키셋이다 */
    List<ChatRoomMember> findActivePage(Long chatRoomId, long afterId, Limit limit);

    /** 방의 활성 참여자 회원 ID */
    List<Long> findActiveUserIds(Long chatRoomId);

    /**
     * 방의 활성 참여자 수. 수신자 집합을 만들 때 같은 트랜잭션의 참여 행 변경을 반영해 센다.
     */
    long countActiveMembers(Long chatRoomId);

    /**
     * 방의 활성 참여 행과 그 회원 복제본. 회원 복제본이 없는 참여 행은 빠진다. 실시간 전달의 수신자 상태를 회원마다 따로 읽지 않고
     * 한 문장으로 읽는다.
     */
    List<MemberWithUser> findActiveWithUser(Long roomId);
}
