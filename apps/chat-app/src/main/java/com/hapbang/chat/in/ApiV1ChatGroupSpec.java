package com.hapbang.chat.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupInviteResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatGroupRequest;
import com.hapbang.chat.in.dto.InviteChatGroupMembersRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 모집용 단체방 REST API Swagger 명세(설계 3.6 협업 초안). 매핑은 {@link ApiV1ChatGroupController}
 * <p>
 * 파라미터 검증(@Valid, @Min 등)은 여기에 둔다. 구현 메서드에서 제약을 새로 정의하면 Bean Validation 규칙 위반으로 요청이 실패한다.
 */
@Tag(name = "채팅 단체방", description = """
        출연 모집글의 호스트가 만드는 모집용 단체방. 경로·필드는 FE와 맞추는 협업 초안이다.
        호스트는 모집글에 지원 이력이 있는 회원(지원 상태 무관)을 찾아 즉시 참여시킨다. 초대 수락 대기와 자유 재입장은 없다.
        방 목록·상세·메시지 이력·읽음·수신 확인·나가기·참여자 페이지는 "채팅" 태그의 공용 API를 쓴다.
        출연 앱이 응답하지 않으면 생성·초대 후보 검색·초대는 503 `APPEARANCE_UNAVAILABLE`이고 아무것도 저장하지 않는다.
        단체방 안내는 `messageType: SYSTEM`이고 `content`는 빈 문자열이다. `notificationKind`에 맞는 값 필드 하나만 채운다:
        GROUP_MEMBERS_INVITED → `groupMembersInvited` `{v, inviterUserId, invitedUserIds}`(초대된 회원도 본다),
        GROUP_MEMBER_KICKED → `groupMemberKicked` `{v, kickedByUserId, kickedUserId}`,
        GROUP_MEMBER_LEFT → `groupMemberLeft` `{v, leftUserId}`, GROUP_ROOM_CLOSED → `groupRoomClosed` `{v, closedByUserId}`.
        프론트가 회원 ID로 닉네임을 찾아 문구를 만든다. 정원·한 요청의 초대 인원·초대 결과 보존 기간은 부하 실측 전의 코드 기본값이다.""")
public interface ApiV1ChatGroupSpec {

    int MAX_CANDIDATE_PAGE_SIZE = 100;

    @Operation(summary = "단체방 생성", description = """
            모집글의 호스트가 단체방을 만든다(201). 호스트·제목(모집글 제목)·게스트 정원은 서버가 정하고 호스트가 첫 참여자다.
            모집글의 현재 방(사용 중·일시 중지)이 있으면 새로 만들지 않고 그 방을 돌려준다(200). 동시에 요청해도 현재 방은 하나다.
            폐쇄한 이전 방은 읽기 전용으로 남고 새 방은 새 ID다. 새로 만들 때마다 모집글이 모집 중인지 다시 확인한다.
            오류: 403 `NOT_RECRUIT_HOST`(모집글·현재 방의 호스트가 아님), 404 `RECRUIT_NOT_FOUND`,
            409 `RECRUIT_NOT_OPEN`(HP 보류 처리 중·실패, 마감, 취소), 503 `APPEARANCE_UNAVAILABLE`.""")
    ResponseEntity<RsData<ChatRoomResponse>> createGroup(Long userId, @Valid CreateChatGroupRequest request);

    @Operation(summary = "모집글의 단체방 조회", description = """
            모집글의 단체방 중 내가 참여 중이고 조회 만료 전인 방을 현재 방(`currentRoom`, 사용 중·일시 중지)과
            이전 방(`previousRooms`, 읽기 전용, 최근 방부터)으로 나눠 준다. 참여 중인 현재 방이 없으면 `currentRoom`은 null이다.
            출연 앱을 부르지 않는다.""")
    RsData<ChatGroupRoomsResponse> getGroups(Long userId,
            @Parameter(description = "모집글 ID")
            @Positive(message = "모집글 ID는 양수여야 합니다.") Long recruitId);

    @Operation(summary = "초대 후보 검색", description = """
            호스트만, 사용 중인 방에서만 쓴다. 모집글에 지원 이력이 있는 회원(대기·수락·미선정·지원 취소 모두) 중
            지금 참여 중이 아닌 회원을 회원 ID 오름차순으로 준다. 나갔거나 내보낸 회원, 초대에 실패한 회원도 다시 나온다.
            `keyword`는 회원 ID 정확 일치 또는 닉네임 부분 일치다(없으면 모두). 닉네임은 같을 수 있으니 회원 ID로 초대한다.
            `invitable`이 false면 `reason`(CHAT_NOT_ALLOWED)이 있다. `remainingGuestSlots`는 남은 게스트 자리 수다.
            이때 모집글 제목이 바뀌었으면 방 제목을 맞춘다(안내 없음).
            오류: 403 `NOT_GROUP_HOST`, 404 `CHAT_ROOM_NOT_FOUND`, 409 `GROUP_ROOM_NOT_ACTIVE`, 503 `APPEARANCE_UNAVAILABLE`.""")
    RsData<ChatInviteCandidatePageResponse> getInviteCandidates(Long userId, Long chatRoomId,
            @Parameter(description = "회원 ID 정확 일치 또는 닉네임 부분 일치. 없으면 모두")
            @Size(max = 50, message = "검색어는 50자 이하여야 합니다.") String keyword,
            @Parameter(description = "이전 응답의 nextCursor(회원 ID). 없으면 처음부터") Long cursor,
            @Parameter(description = "페이지 크기 (기본 20)")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_CANDIDATE_PAGE_SIZE, message = "size는 " + MAX_CANDIDATE_PAGE_SIZE + " 이하여야 합니다.")
            int size);

    @Operation(summary = "초대", description = """
            호스트만, 사용 중인 방에서만 쓴다. 초대하면 즉시 참여한다. 중복 ID는 한 번만 처리하고 요청 순서대로 빈자리를 채운다.
            회원별 결과 `JOINED`(참여)·`ALREADY_MEMBER`(이미 참여 중, 다시 처리·안내하지 않음)·`FAILED`(사유 `NOT_APPLICANT`·
            `CHAT_NOT_ALLOWED`·`ROOM_FULL`)를 돌려준다. 일부가 실패해도 200이다. 나갔거나 내보낸 회원은 새 참여 세대로 들어오고
            이전 메시지는 보이지 않는다. 참여한 회원이 있으면 초대 안내(GROUP_MEMBERS_INVITED) 하나를 남기고 초대된 회원도 본다.
            `requestId`는 멱등 키다. 보존 기간(기본 24시간) 안에 같은 값을 다시 보내면 저장한 결과를 그대로 돌려주고 다시 초대하지 않는다
            (그 사이 나가거나 내보낸 회원도 다시 넣지 않는다). 새 초대는 새 값을 쓴다.
            오류: 400 `INVALID_REQUEST`(요청 ID·회원 누락, 한 요청 상한 초과(기본 50명, 중복 제외)), 403 `NOT_GROUP_HOST`,
            404 `CHAT_ROOM_NOT_FOUND`, 409 `GROUP_ROOM_NOT_ACTIVE`, 503 `APPEARANCE_UNAVAILABLE`.""")
    RsData<ChatGroupInviteResponse> invite(Long userId, Long chatRoomId,
            @Valid InviteChatGroupMembersRequest request);

    @Operation(summary = "강퇴", description = """
            호스트만, 사용 중인 방에서만 쓴다. `participationVersion`에는 참여자 페이지의 그 회원 값을 보낸다.
            현재 참여 세대와 다르면 409 `STALE_PARTICIPATION`이고 바꾸지 않는다(옛 요청이 재초대된 참여를 끝내지 않는다).
            같은 세대에서 이미 나갔거나 내보냈으면 204다(멱등). 남은 참여자에게 강퇴 안내(GROUP_MEMBER_KICKED)가 간다.
            내보낸 회원은 호스트가 다시 초대해야만 들어온다. 출연 지원 상태·HP는 바뀌지 않는다.
            오류: 400 `CANNOT_KICK_HOST`, 403 `NOT_GROUP_HOST`, 404 `CHAT_ROOM_NOT_FOUND`, 404 `KICK_TARGET_NOT_MEMBER`,
            409 `GROUP_ROOM_NOT_ACTIVE`, 409 `STALE_PARTICIPATION`.""")
    ResponseEntity<Void> kick(Long userId, Long chatRoomId, Long targetUserId,
            @Parameter(description = "내보낼 회원의 참여 세대(참여자 페이지의 participationVersion)")
            @Min(value = 1, message = "참여 세대는 1 이상이어야 합니다.") Integer participationVersion);

    @Operation(summary = "폐쇄", description = """
            호스트가 방을 읽기 전용(READ_ONLY, 사유 CLOSED_BY_HOST)으로 바꾼다. 참여자를 내보내지 않는다.
            나가지 않은 참여자는 `historyExpiresAt`(전환 시각 + 30×24시간) 전까지 자기 가시 범위를 조회·읽음 처리하고 나갈 수 있다.
            새 메시지·초대·강퇴는 할 수 없다. 폐쇄 안내(GROUP_ROOM_CLOSED)가 방의 마지막 메시지다.
            폐쇄 뒤에는 같은 모집글에 새 방을 만들 수 있고, 호스트도 이 방을 나갈 수 있다. 이미 폐쇄했으면 같은 상태로 200이다.
            강등·정지된 호스트도 폐쇄할 수 있다.
            오류: 403 `NOT_GROUP_HOST`, 404 `CHAT_ROOM_NOT_FOUND`(없음·조회 만료), 409 `GROUP_ROOM_NOT_ACTIVE`(그 밖의 읽기 전용).""")
    RsData<ChatGroupCloseResponse> close(Long userId, Long chatRoomId);
}
