package com.hapbang.chat.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.app.ChatMessageFacade;
import com.hapbang.chat.app.ChatRoomFacade;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.websocket.ChatMessageSender;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

// TODO(security): 회원 ID는 X-User-Id 헤더 대신 SecurityContext(@AuthenticationPrincipal 등)에서 꺼낸다.
// TODO(security): 채팅 API는 인플루언서·인증된 인플루언서·관리자 Role만 허용한다(AGENTS.md §6). 지금은 복제한 ChatUser 역할로만 막는다.
// TODO(security): global SecurityConfig에 /api/v1/chats/**(웹소켓 /api/v1/chats/ws 포함) 인가 규칙을 추가해야 한다(현재는 Swagger 외 모두 authenticated).
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ChatRoomController {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatRoomFacade chatRoomFacade;
    private final ChatMessageFacade chatMessageFacade;
    private final ChatMessageSender chatMessageSender;

    /**
     * 1:1 채팅을 시작한다. 새로 만들면 201, 종료되지 않은 기존 방을 재사용하면 200.
     */
    @PostMapping
    public ResponseEntity<ChatRoomResponse> createChatRoom(@RequestHeader(ChatHeaders.USER_ID) Long userId,
            @Valid @RequestBody CreateChatRoomRequest request) {
        ChatRoomCreateResult result = chatRoomFacade.createOrGet(userId, request.targetUserId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.room());
    }

    @GetMapping
    public List<ChatRoomResponse> getMyChatRooms(@RequestHeader(ChatHeaders.USER_ID) Long userId) {
        return chatRoomFacade.getMyRooms(userId);
    }

    @GetMapping("/{chatRoomId}/messages")
    public ChatMessagePageResponse getMessages(@RequestHeader(ChatHeaders.USER_ID) Long userId,
            @PathVariable("chatRoomId") Long chatRoomId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_PAGE_SIZE, message = "size는 " + MAX_PAGE_SIZE + " 이하여야 합니다.") int size) {
        return chatMessageFacade.getMessages(chatRoomId, userId, cursor, size);
    }

    /**
     * 채팅방을 나간다. 남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
     * 이미 나갔거나 옛 참여 세대의 요청이어도 204로 응답한다(멱등).
     *
     * @param participationVersion 참여 세대. 보내면 지연 도착한 옛 요청이 새 참여를 끝내지 않는다.
     */
    @DeleteMapping("/{chatRoomId}/members/me")
    public ResponseEntity<Void> leaveChatRoom(@RequestHeader(ChatHeaders.USER_ID) Long userId,
            @PathVariable("chatRoomId") Long chatRoomId,
            @RequestParam(name = "participationVersion", required = false) Integer participationVersion) {
        ChatRoomLeaveResult result = chatRoomFacade.leave(chatRoomId, userId, participationVersion);
        if (result.left()) {
            chatMessageSender.deliver(result.leftNotice(), result.remainingUserIds());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 관리자 분쟁 확인용 기록 조회.
     */
    // TODO(security): 관리자 Role(hasRole('ADMIN'))로 막는다. 지금은 서비스에서 ChatUser 역할로만 확인한다.
    @GetMapping("/{chatRoomId}/history")
    public ChatRoomHistoryResponse getHistory(@RequestHeader(ChatHeaders.USER_ID) Long userId,
            @PathVariable("chatRoomId") Long chatRoomId) {
        return chatRoomFacade.getHistory(chatRoomId, userId);
    }
}
