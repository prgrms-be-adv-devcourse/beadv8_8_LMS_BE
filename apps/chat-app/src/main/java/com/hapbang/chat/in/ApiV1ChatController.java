package com.hapbang.chat.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.app.ChatMessageFacade;
import com.hapbang.chat.app.ChatReceiptFacade;
import com.hapbang.chat.app.ChatRoomFacade;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReadChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.chat.global.auth.LoginUserId;
import com.hapbang.chat.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 REST API. 회원 ID는 팀 공통 로그인 세션에서 {@link LoginUserId}로 받는다.
 * 성공은 {@link RsData}로, 오류는 global 공통 핸들러가 ProblemDetail로 응답한다. 채팅방 나가기는 본문 없이 204다.
 * 로그인하지 않은 요청은 global SecurityConfig({@code anyRequest().authenticated()})에서 401로 막힌다.
 * <p>
 * 역할 검사는 두 번 한다. Facade의 {@code @PreAuthorize}로 로그인 세션의 역할을 먼저 확인하고(팀 규칙),
 * UseCase에서 채팅 회원({@code ChatUser})의 복제된 역할로 다시 확인한다. 세션 역할은 로그인 시점 값이라
 * 로그인 이후 강등·정지된 경우는 UseCase 검사가 막는다.
 */
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ApiV1ChatController implements ApiV1ChatSpec {

    private static final int DEFAULT_PAGE_SIZE = 30;

    private final ChatRoomFacade chatRoomFacade;
    private final ChatMessageFacade chatMessageFacade;
    private final ChatMessageSender chatMessageSender;
    private final ChatReceiptFacade chatReceiptFacade;

    /**
     * 1:1 채팅을 시작한다. 새로 만들면 201, 종료되지 않은 기존 방을 재사용하면 200.
     * 퇴장했던 회원이 재입장하면 남아 있던 회원에게 재입장 안내가 실시간으로 간다.
     */
    @Override
    @PostMapping
    public ResponseEntity<RsData<ChatRoomResponse>> createChatRoom(@LoginUserId Long userId,
                                                                   @RequestBody CreateChatRoomRequest request) {
        ChatRoomCreateResult result = chatRoomFacade.createOrGet(userId, request.targetUserId());
        for (ChatMessageResponse rejoinNotice : result.rejoinNotices()) {
            chatMessageSender.deliver(rejoinNotice, result.remainingUserIds());
        }
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "채팅방을 만들었습니다." : "기존 채팅방을 불러왔습니다.";
        return ResponseEntity.status(status).body(RsData.of(status, message, result.room()));
    }

    @Override
    @GetMapping
    public RsData<List<ChatRoomResponse>> getMyChatRooms(@LoginUserId Long userId) {
        return RsData.ok("내 채팅방 목록을 조회했습니다.", chatRoomFacade.getMyRooms(userId));
    }

    @Override
    @GetMapping("/{chatRoomId}/messages")
    public RsData<ChatMessagePageResponse> getMessages(@LoginUserId Long userId,
                                                       @PathVariable("chatRoomId") Long chatRoomId,
                                                       @RequestParam(name = "cursor", required = false) Long cursor,
                                                       @RequestParam(name = "size",
                                                               defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return RsData.ok("메시지 이력을 조회했습니다.", chatMessageFacade.getMessages(chatRoomId, userId, cursor, size));
    }

    /**
     * 메시지를 읽음 처리한다. 방 목록의 안 읽은 수가 이 순번 뒤의 상대 메시지 수로 바뀐다. 본문 없이 204다.
     * 참여 세대를 보내면 현재 세대일 때만 반영한다.
     */
    @Override
    @PutMapping("/{chatRoomId}/read")
    public ResponseEntity<Void> markRead(@LoginUserId Long userId,
                                         @PathVariable("chatRoomId") Long chatRoomId,
                                         @RequestBody ReadChatRoomRequest request) {
        chatRoomFacade.markRead(chatRoomId, userId, request.lastReadSequence(), request.participationVersion());
        return ResponseEntity.noContent().build();
    }

    /**
     * 누적 수신 위치를 반영한다. 회원은 로그인 세션으로 정한다. 같은 값·낮은 값은 현재 값으로 200이다(멱등).
     */
    @Override
    @PutMapping("/{chatRoomId}/received")
    public RsData<ChatReceiptResponse> markReceived(@LoginUserId Long userId,
                                                    @PathVariable("chatRoomId") Long chatRoomId,
                                                    @RequestBody ReceivedChatRoomRequest request) {
        return RsData.ok("수신 위치를 반영했습니다.", chatReceiptFacade.markReceived(chatRoomId, userId,
                request.participationVersion(), request.receivedSequence()));
    }

    /**
     * 채팅방을 나간다. 남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
     * 이미 나갔거나 옛 참여 세대의 요청이어도 204로 응답한다(멱등).
     * 강등·정지된 회원도 나갈 수 있게 역할 검사를 하지 않는다(참여자 확인은 UseCase에서 한다).
     *
     * @param participationVersion 참여 세대. 보내면 지연 도착한 옛 요청이 새 참여를 끝내지 않는다.
     */
    @Override
    @DeleteMapping("/{chatRoomId}/members/me")
    public ResponseEntity<Void> leaveChatRoom(@LoginUserId Long userId,
                                              @PathVariable("chatRoomId") Long chatRoomId,
                                              @RequestParam(name = "participationVersion", required = false)
                                              Integer participationVersion) {
        ChatRoomLeaveResult result = chatRoomFacade.leave(chatRoomId, userId, participationVersion);
        if (result.left()) {
            chatMessageSender.deliver(result.leftNotice(), result.remainingUserIds());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 관리자 분쟁 확인용 기록 조회. 관리자 API라 SecurityConfig의 {@code /api/v1/{module}/admin/**} 제한도 함께 받는다.
     */
    @Override
    @GetMapping("/admin/{chatRoomId}/history")
    public RsData<ChatRoomHistoryResponse> getHistory(@LoginUserId Long userId,
                                                      @PathVariable("chatRoomId") Long chatRoomId,
                                                      @RequestParam(name = "cursor", required = false) String cursor,
                                                      @RequestParam(name = "size",
                                                              defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return RsData.ok("채팅 기록을 조회했습니다.", chatRoomFacade.getHistory(chatRoomId, userId, cursor, size));
    }
}
