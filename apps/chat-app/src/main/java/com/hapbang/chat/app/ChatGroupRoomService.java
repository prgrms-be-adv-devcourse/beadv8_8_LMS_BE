package com.hapbang.chat.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.hapbang.chat.app.ChatRecruitSource.ChatRecruit;
import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;

/**
 * 모집용 단체방의 생성·조회·초대·강퇴·폐쇄 (설계 3장). 출연 앱 호출은 하지 않는다. {@link ChatGroupRoomFacade}가 트랜잭션 밖에서
 * 출연 정보({@link ChatRecruitSource})를 읽어 넘긴다. 쓰기는 방을 잠근 한 트랜잭션에서 참여 행·인원·수신자 집합·안내·전달 작업·기록을
 * 함께 저장한다.
 */
public interface ChatGroupRoomService {

    /**
     * 모집글의 현재 방(사용 중·일시 중지). 생성 요청 전에 출연 앱을 부르지 않고 기존 방을 돌려주려고 먼저 본다.
     *
     * @return 요청한 회원이 호스트인 현재 방의 ID. 없으면 빈 값
     * @throws com.hapbang.chat.app.exception.ChatException 현재 방의 호스트가 아니면 {@code NOT_RECRUIT_HOST}
     */
    Optional<Long> findCurrentRoomOf(Long requesterId, Long recruitId);

    /**
     * 단체방을 만든다. 호스트가 첫 참여자다. 현재 방이 이미 있으면 그 방을 돌려준다. 동시에 만들면 부분 유일 인덱스에 걸리므로
     * 호출한 Facade가 다시 시도한다.
     *
     * @param recruit 출연 앱에서 확인한 모집글(요청한 회원이 호스트이고 모집 중)
     */
    ChatRoomOpenResult createRoom(Long requesterId, ChatRecruit recruit);

    /** 모집글의 단체방 중 조회한 회원이 볼 수 있는 현재 방과 이전 방 */
    ChatGroupRoomsResponse getRooms(Long userId, Long recruitId);

    /**
     * 호스트가 관리할 수 있는 사용 중인 방인지 확인한다(초대 후보 검색·초대 전).
     *
     * @return 방의 모집글 ID
     */
    Long checkManageable(Long chatRoomId, Long requesterId);

    /**
     * 초대 후보 한 페이지. 모집글 제목이 바뀌었으면 방 제목을 맞춘다(안내 없음).
     *
     * @param applicantUserIds 출연 앱에서 읽은 지원 이력이 있는 회원 전부(지원 상태 무관)
     * @param keyword          회원 ID 정확 일치 또는 닉네임 부분 일치. 비어 있으면 모두
     * @param cursor           이전 응답의 nextCursor(회원 ID). 없으면 처음부터
     */
    ChatInviteCandidatePageResponse findInviteCandidates(Long chatRoomId, Long requesterId, String recruitTitle,
            Collection<Long> applicantUserIds, String keyword, Long cursor, int size);

    /**
     * 초대 전 확인. 호스트인지 보고, 보존 기간 안에 같은 요청 ID로 저장한 결과가 있으면 함께 돌려준다(그때는 출연 앱을 부르지 않는다).
     * 저장한 결과가 없으면 사용 중인 방인지도 확인한다.
     */
    InvitePreparation prepareInvite(Long chatRoomId, Long requesterId, UUID requestId);

    /**
     * 초대한다. 회원별로 이미 참여 중·지원 이력·채팅 자격·정원(요청 순서대로 빈자리)을 검사해 결과를 돌려준다(Q34). 같은 요청 ID를
     * 보존 기간 안에 다시 받으면 저장한 결과를 돌려주고 다시 초대하지 않는다.
     *
     * @param userIds        중복을 뺀 요청 순서의 회원 ID
     * @param appliedUserIds 출연 앱이 확인한, 그중 지원 이력이 있는 회원
     */
    List<ChatGroupInviteOutcome> invite(Long chatRoomId, Long requesterId, UUID requestId, List<Long> userIds,
            Set<Long> appliedUserIds);

    /**
     * 내보낸다. 대상의 참여 세대가 요청과 같을 때만 내보낸다. 이미 그 세대에서 나갔으면 바꾸지 않는다(멱등).
     *
     * @return 이번 요청으로 내보냈으면 끝난 참여 세대, 아니면 빈 값
     */
    Optional<Integer> kick(Long chatRoomId, Long requesterId, Long targetUserId, int participationVersion);

    /** 폐쇄한다(읽기 전용 전환, 30×24시간 뒤 참여자 조회 만료). 이미 직접 폐쇄한 방이면 그 상태를 돌려준다 */
    ChatGroupCloseResponse close(Long chatRoomId, Long requesterId);

    /**
     * 호스트가 탈퇴하거나 호스트 자격이 없는 역할이 되면 그 회원의 현재 단체방(사용 중·일시 중지)을 모두 읽기 전용으로 바꾼다
     * (설계 3.1 "호스트 탈퇴·자격 상실", Q31). 방마다 자격 상실 안내(값만)·방 기록·전달 작업을 남긴다. 회원 반영과 같은 트랜잭션에서만
     * 부른다(MANDATORY). 이미 읽기 전용·만료인 방은 대상이 아니므로 같은 반영이 다시 와도 아무것도 바꾸지 않는다.
     *
     * @return 읽기 전용으로 바꾼 방 수
     */
    int markHostUnavailable(Long hostUserId);

    /** 저장이 커밋된 뒤의 방 응답. 별도 읽기 트랜잭션이다 */
    ChatRoomResponse toRoomResponse(Long chatRoomId, Long userId);

    /**
     * @param recruitId 방의 모집글 ID
     * @param retained  보존 기간 안에 같은 요청 ID로 저장한 결과. 없으면 null
     */
    record InvitePreparation(Long recruitId, List<ChatGroupInviteOutcome> retained) {
    }
}
