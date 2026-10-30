package com.hapbang.chat.in;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReadChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.global.response.RsData;

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
        보내는 본문(`content`)은 공백만으로 이뤄질 수 없고 2,000자 이하다. 넘으면 오류 큐로 INVALID_REQUEST가 `clientMessageId`와 함께 온다.
        보낼 때 `clientMessageId`(선택, 64자 이하)를 붙이면 보낸 사람에게 전달되는 메시지와 오류에 그대로 돌려준다.
        같은 참여 안에서 같은 `clientMessageId`로 다시 보내면 새로 저장하지 않고 기존 메시지를 보낸 사람에게만 다시 보낸다(알림 없음, 본문이 달라도 기존 메시지).
        나갔다가 재입장한 뒤에는 이전에 쓴 `clientMessageId`로 보내도 새 메시지로 저장한다.
        `clientMessageId`는 보낸 사람 본인 메시지에만 담긴다(실시간 전달·메시지 이력·방 목록의 마지막 메시지).
        출연 요청 도착·모집 지원 도착은 호스트-게스트 1:1 방에 알림 메시지(`messageType: NOTIFICATION`)로 온다.
        `notificationKind`와 `referenceId`·`parentReferenceId`로 출연 건을 찾는다:
        APPEARANCE_REQUEST_SENT는 `referenceId` = 요청서 ID, APPEARANCE_APPLICATION_SUBMITTED는 `referenceId` = 지원 ID·`parentReferenceId` = 모집글 ID.
        서버는 알림 문구를 만들지 않는다. 알림 종류마다 값 필드가 하나씩 있고 `notificationKind`에 맞는 필드만 채운다(나머지는 null).
        APPEARANCE_REQUEST_SENT → `appearanceRequestSent` `{v, hostNickname, title, fee(숫자, HP), scheduledAt, acceptDeadline}`,
        APPEARANCE_APPLICATION_SUBMITTED → `appearanceApplicationSubmitted` `{v, guestNickname, recruitTitle}`.
        일시는 KST의 `yyyy-MM-dd'T'HH:mm:ss`(오프셋 없음)다. 값이 있는 알림의 `content`는 빈 문자열이다.
        프론트는 템플릿을 만들 수 있으면 템플릿, 아니면 비어 있지 않은 `content`(문구로 남긴 기존 알림), 그것도 비면 일반 문구 순으로 보여 준다.""")
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
            다음 페이지는 응답의 `nextCursor`를 `cursor`로 보낸다. `nextCursor`가 null이면 마지막 페이지다.
            `participationVersion`은 현재 참여 세대, `visibleFromSequence`는 볼 수 있는 첫 메시지 순번이다.
            세대가 바뀌면 이전 세대의 캐시·확인 위치를 버리고, `visibleFromSequence` 앞 순번은 누락으로 보지 않는다.""")
    RsData<ChatMessagePageResponse> getMessages(Long userId, Long chatRoomId,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 가장 최근부터") Long cursor,
            @Parameter(description = "페이지 크기 (기본 30)")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_PAGE_SIZE, message = "size는 " + MAX_PAGE_SIZE + " 이하여야 합니다.") int size);

    @Operation(summary = "읽음 처리", description = """
            화면에 보여 준 가장 최근 메시지의 `roomSequence`를 보낸다. 지금보다 작은 값은 무시하고(줄어들지 않음),
            방의 마지막 순번보다 큰 값은 마지막 순번으로 처리한다. 같은 값·낮은 값도 204다.
            `participationVersion`(선택, 1 이상)에는 메시지 이력 응답의 값을 보낸다. 보내면 현재 참여 세대일 때만 반영하고,
            다르면 아무것도 바꾸지 않고 409 `STALE_PARTICIPATION`이다. 보내지 않으면 처리 시점의 현재 세대로 본다(전환 전 호환).
            처리 중 재입장하면 409 `STALE_PARTICIPATION`, 퇴장하면 403 `NOT_CHAT_ROOM_MEMBER`이며 반영하지 않는다.
            409면 그 방의 이전 세대 상태를 버리고 방 목록·이력을 다시 동기화하며, 읽음 요청을 다시 보내지 않는다.
            오류: 400 `INVALID_REQUEST`(`lastReadSequence` 누락·음수, `participationVersion` 1 미만), 403 `CHAT_NOT_ALLOWED`,
            403 `NOT_CHAT_ROOM_MEMBER`, 404 `CHAT_ROOM_NOT_FOUND`, 409 `STALE_PARTICIPATION`.""")
    ResponseEntity<Void> markRead(Long userId, Long chatRoomId, @Valid ReadChatRoomRequest request);

    @Operation(summary = "수신 확인", description = """
            이 기기가 현재 참여 세대의 가시 범위에서 빠짐없이 반영한 연속 구간의 끝 `roomSequence`를 보낸다(누적 ACK).
            99까지 확인한 뒤 101만 받았으면 100을 확인하기 전에는 101을 보내지 않는다. 같은 방의 확인은 기기에서 합쳐 보낸다.
            `participationVersion`은 메시지 이력 응답의 값을 쓴다. 회원은 로그인 세션으로 정한다.
            응답의 `lastReceivedSequence`는 반영 뒤 서버의 회원 수신 위치다. 다른 기기가 더 높게 올렸으면 요청 값보다 클 수 있다.
            같은 값·낮은 값은 바꾸지 않고 현재 값으로 200이다(멱등). 이 값은 "적어도 한 기기가 확인한 위치"라 기기의 확인 위치로 복사하지 않는다.
            읽음 처리·안 읽은 수와 독립이다.
            오류: 400 `INVALID_REQUEST`(필드 누락·음수, `participationVersion` 1 미만), 400 `RECEIVED_SEQUENCE_OUT_OF_RANGE`(조회 시작점보다 작거나 방의 마지막 순번 초과, 재동기화 뒤 다시 계산),
            403 `CHAT_NOT_ALLOWED`, 403 `NOT_CHAT_ROOM_MEMBER`(참여 중이 아님), 404 `CHAT_ROOM_NOT_FOUND`(방 없음·종료),
            409 `STALE_PARTICIPATION`(현재 참여 세대가 아님, 이전 세대의 캐시·확인 위치를 버리고 방 목록과 이력을 다시 동기화).""")
    RsData<ChatReceiptResponse> markReceived(Long userId, Long chatRoomId, @Valid ReceivedChatRoomRequest request);

    @Operation(summary = "채팅방 나가기", description = """
            남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
            이미 나갔어도 204로 응답한다. 강등·정지된 회원도 나갈 수 있다.""")
    ResponseEntity<Void> leaveChatRoom(Long userId, Long chatRoomId,
            @Parameter(description = "참여 세대. 보내면 지연 도착한 옛 요청이 재입장한 참여를 끝내지 않는다")
            Integer participationVersion);

    @Operation(summary = "채팅 기록 조회 (관리자)", description = """
            분쟁 확인용. 종료된 방과 참여자별로 숨겨진 메시지까지 포함한다. 관리자만 쓸 수 있다.
            메시지와 입퇴장 기록을 하나의 시간순 흐름(시각, 같은 시각이면 기록 → 메시지)으로 보고 오래된 것부터 나눠 준다.
            `logs`·`messages`는 이 페이지 구간의 항목이고, 다음 페이지는 응답의 `nextCursor`를 `cursor`로 보낸다.
            `nextCursor`가 null이면 마지막 페이지다. 형식이 틀린 커서는 400(INVALID_REQUEST).
            알림 메시지는 회원 응답과 같이 종류별 값 필드(`appearanceRequestSent`·`appearanceApplicationSubmitted`)를 담고,
            값이 있으면 `content`는 빈 문자열이다.""")
    RsData<ChatRoomHistoryResponse> getHistory(Long userId, Long chatRoomId,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 처음부터") String cursor,
            @Parameter(description = "페이지 크기 (기본 30, 기록과 메시지를 합친 개수)")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_PAGE_SIZE, message = "size는 " + MAX_PAGE_SIZE + " 이하여야 합니다.") int size);
}
