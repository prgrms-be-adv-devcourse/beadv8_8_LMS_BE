package com.hapbang.chat.in;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReadChatRoomRequest;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 채팅 REST API Swagger 명세. 매핑은 {@link ApiV1ChatController}
 * <p>
 * 파라미터 검증(@Valid, @Min 등)은 여기에 둔다. 구현 메서드에서 제약을 새로 정의하면 Bean Validation 규칙 위반으로 요청이 실패한다.
 */
@Tag(name = "채팅", description = """
        1:1 채팅방과 메시지 이력. 인플루언서 이상 또는 관리자만 쓸 수 있다.
        실시간 송수신은 STOMP라 여기에 없다: 연결 `/api/v1/chats/ws`, 보내기 `/pub/chats/{chatRoomId}/messages`,
        받기 `/user/queue/chats/messages`(메시지)·`/user/queue/chats/notifications`(새 메시지 알림)·`/user/queue/chats/errors`(오류).
        보낼 때 `clientMessageId`(선택, 64자 이하)를 붙이면 전달되는 메시지와 오류에 그대로 돌려준다.""")
public interface ApiV1ChatSpec {

    int MAX_PAGE_SIZE = 100;

    @Operation(summary = "채팅방 생성", description = """
            상대와의 종료되지 않은 방이 있으면 재사용하고(200), 없으면 새로 만든다(201).
            퇴장했던 방이면 재입장하며, 재입장 이전 메시지는 볼 수 없다.
            재입장하면 남아 있던 회원에게 재입장 안내(SYSTEM, "{닉네임}님이 다시 들어왔습니다.")가 `/user/queue/chats/messages`로 간다.
            재입장한 본인에게는 안내가 보이지 않는다.""")
    ResponseEntity<RsData<ChatRoomResponse>> createChatRoom(Long userId, @Valid CreateChatRoomRequest request);

    @Operation(summary = "내 채팅방 목록", description = """
            현재 참여 중인 채팅방만 조회한다. 인플루언서 이상 또는 관리자만 쓸 수 있다.
            `lastMessage`는 내가 볼 수 있는 가장 최근 메시지(없으면 null), `unreadCount`는 읽음 처리 이후 온 상대 메시지 수다.""")
    RsData<List<ChatRoomResponse>> getMyChatRooms(Long userId);

    @Operation(summary = "메시지 이력 조회", description = """
            최신순으로 조회한다. 본인이 볼 수 있는 범위(재입장 이후)의 메시지만 나온다.
            다음 페이지는 응답의 `nextCursor`를 `cursor`로 보낸다. `nextCursor`가 null이면 마지막 페이지다.""")
    RsData<ChatMessagePageResponse> getMessages(Long userId, Long chatRoomId,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 가장 최근부터") Long cursor,
            @Parameter(description = "페이지 크기 (기본 30)")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_PAGE_SIZE, message = "size는 " + MAX_PAGE_SIZE + " 이하여야 합니다.") int size);

    @Operation(summary = "읽음 처리", description = """
            화면에 보여 준 가장 최근 메시지의 `roomSequence`를 보낸다. 지금보다 작은 값은 무시하고(줄어들지 않음),
            방의 마지막 순번보다 큰 값은 마지막 순번으로 처리한다.""")
    ResponseEntity<Void> markRead(Long userId, Long chatRoomId, @Valid ReadChatRoomRequest request);

    @Operation(summary = "채팅방 나가기", description = """
            남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
            이미 나갔어도 204로 응답한다. 강등·정지된 회원도 나갈 수 있다.""")
    ResponseEntity<Void> leaveChatRoom(Long userId, Long chatRoomId,
            @Parameter(description = "참여 세대. 보내면 지연 도착한 옛 요청이 재입장한 참여를 끝내지 않는다")
            Integer participationVersion);

    @Operation(summary = "채팅 기록 조회 (관리자)",
            description = "분쟁 확인용. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다. 관리자만 쓸 수 있다.")
    RsData<ChatRoomHistoryResponse> getHistory(Long userId, Long chatRoomId);
}
