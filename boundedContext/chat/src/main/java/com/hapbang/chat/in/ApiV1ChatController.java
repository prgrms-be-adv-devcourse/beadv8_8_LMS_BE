package com.hapbang.chat.in;

import java.util.List;

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

import com.hapbang.chat.app.ChatMessageFacade;
import com.hapbang.chat.app.ChatRoomFacade;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.global.auth.LoginUserId;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 REST API. 회원 ID는 팀 공통 로그인 세션에서 {@link LoginUserId}로 받는다.
 * 로그인하지 않은 요청은 global SecurityConfig({@code anyRequest().authenticated()})에서 401로 막힌다.
 */
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ApiV1ChatController implements ApiV1ChatSwagger {

    private static final int DEFAULT_PAGE_SIZE = 30;

    private final ChatRoomFacade chatRoomFacade;
    private final ChatMessageFacade chatMessageFacade;
    private final ChatMessageSender chatMessageSender;

    /**
     * 1:1 채팅을 시작한다. 새로 만들면 201, 종료되지 않은 기존 방을 재사용하면 200.
     */
    @Override
    @PostMapping
    public ResponseEntity<ChatRoomResponse> createChatRoom(@LoginUserId Long userId,
            @RequestBody CreateChatRoomRequest request) {
        ChatRoomCreateResult result = chatRoomFacade.createOrGet(userId, request.targetUserId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.room());
    }

    @Override
    @GetMapping
    public List<ChatRoomResponse> getMyChatRooms(@LoginUserId Long userId) {
        return chatRoomFacade.getMyRooms(userId);
    }

    @Override
    @GetMapping("/{chatRoomId}/messages")
    public ChatMessagePageResponse getMessages(@LoginUserId Long userId,
            @PathVariable("chatRoomId") Long chatRoomId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return chatMessageFacade.getMessages(chatRoomId, userId, cursor, size);
    }

    /**
     * 채팅방을 나간다. 남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
     * 이미 나갔거나 옛 참여 세대의 요청이어도 204로 응답한다(멱등).
     *
     * @param participationVersion 참여 세대. 보내면 지연 도착한 옛 요청이 새 참여를 끝내지 않는다.
     */
    @Override
    @DeleteMapping("/{chatRoomId}/members/me")
    public ResponseEntity<Void> leaveChatRoom(@LoginUserId Long userId,
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
    @Override
    @GetMapping("/{chatRoomId}/history")
    public ChatRoomHistoryResponse getHistory(@LoginUserId Long userId,
            @PathVariable("chatRoomId") Long chatRoomId) {
        return chatRoomFacade.getHistory(chatRoomId, userId);
    }
}
