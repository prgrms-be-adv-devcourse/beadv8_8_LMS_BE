package com.hapbang.chat.in;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.app.ChatGroupRoomFacade;
import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupInviteResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.global.auth.LoginUserId;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatGroupRequest;
import com.hapbang.chat.in.dto.InviteChatGroupMembersRequest;

import lombok.RequiredArgsConstructor;

/**
 * 모집용 단체방 REST API. 공용 채팅 API({@link ApiV1ChatController})와 같은 경로 아래에 두고, 회원 ID는 {@link LoginUserId}로 받는다.
 * 성공은 {@link RsData}, 오류는 global 공통 핸들러의 ProblemDetail이다. 강퇴는 본문 없이 204다.
 * 자기 나가기({@code DELETE /{chatRoomId}/members/me})는 공용 API에 있다. 경로 변수보다 고정 경로가 먼저 고른다.
 */
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ApiV1ChatGroupController implements ApiV1ChatGroupSpec {

    private static final int DEFAULT_CANDIDATE_PAGE_SIZE = 20;

    private final ChatGroupRoomFacade chatGroupRoomFacade;

    /** 새로 만들면 201, 모집글의 현재 방을 돌려주면 200 */
    @Override
    @PostMapping("/groups")
    public ResponseEntity<RsData<ChatRoomResponse>> createGroup(@LoginUserId Long userId,
                                                                @RequestBody CreateChatGroupRequest request) {
        ChatRoomCreateResult result = chatGroupRoomFacade.createRoom(userId, request.recruitId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "단체방을 만들었습니다." : "기존 단체방을 불러왔습니다.";
        return ResponseEntity.status(status).body(RsData.of(status, message, result.room()));
    }

    @Override
    @GetMapping("/groups")
    public RsData<ChatGroupRoomsResponse> getGroups(@LoginUserId Long userId,
                                                    @RequestParam(name = "recruitId") Long recruitId) {
        return RsData.ok("모집글의 단체방을 조회했습니다.", chatGroupRoomFacade.getRooms(userId, recruitId));
    }

    @Override
    @GetMapping("/{chatRoomId}/invite-candidates")
    public RsData<ChatInviteCandidatePageResponse> getInviteCandidates(@LoginUserId Long userId,
            @PathVariable("chatRoomId") Long chatRoomId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_CANDIDATE_PAGE_SIZE) int size) {
        return RsData.ok("초대 후보를 조회했습니다.",
                chatGroupRoomFacade.getInviteCandidates(chatRoomId, userId, keyword, cursor, size));
    }

    /** 일부 회원이 실패해도 200이다(회원별 결과) */
    @Override
    @PostMapping("/{chatRoomId}/members")
    public RsData<ChatGroupInviteResponse> invite(@LoginUserId Long userId,
                                                  @PathVariable("chatRoomId") Long chatRoomId,
                                                  @RequestBody InviteChatGroupMembersRequest request) {
        return RsData.ok("초대를 처리했습니다.",
                chatGroupRoomFacade.invite(chatRoomId, userId, request.requestId(), request.userIds()));
    }

    @Override
    @DeleteMapping("/{chatRoomId}/members/{targetUserId}")
    public ResponseEntity<Void> kick(@LoginUserId Long userId,
                                     @PathVariable("chatRoomId") Long chatRoomId,
                                     @PathVariable("targetUserId") Long targetUserId,
                                     @RequestParam(name = "participationVersion") Integer participationVersion) {
        chatGroupRoomFacade.kick(chatRoomId, userId, targetUserId, participationVersion);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PostMapping("/{chatRoomId}/close")
    public RsData<ChatGroupCloseResponse> close(@LoginUserId Long userId,
                                                @PathVariable("chatRoomId") Long chatRoomId) {
        return RsData.ok("단체방을 폐쇄했습니다.", chatGroupRoomFacade.close(chatRoomId, userId));
    }
}
