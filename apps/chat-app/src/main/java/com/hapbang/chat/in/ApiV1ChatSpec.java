package com.hapbang.chat.in;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberPageResponse;
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
        1:1 채팅방·모집용 단체방 공용 API와 메시지 이력. 인플루언서 이상 또는 관리자만 쓸 수 있다. 단체방 전용 API는 "채팅 단체방" 태그에 있다.
        단체방에서는 사용 중(ACTIVE)일 때만 보낼 수 있다(호스트 혼자여도 된다). 읽기 전용·일시 중지 방에 보내면 오류 큐로 GROUP_ROOM_NOT_ACTIVE가 온다.
        단체방의 방 topic 구독·읽음·수신 확인은 조회 만료 전까지 읽기 전용·일시 중지 방에서도 된다.
        실시간 송수신은 STOMP라 여기에 없다: 연결 `/api/v1/chats/ws?ticket={웹소켓 연결 티켓}`(티켓은 아래 발급 API), 보내기 `/pub/chats/{chatRoomId}/messages`,
        받기: 열어 둔 방은 `/topic/chats/rooms/{chatRoomId}`(SUBSCRIBE 헤더 `participation-version` 필수, 방 메시지·구독 거절·회수),
        `/user/queue/chats/messages`(자기가 보낸 메시지의 전송 결과 확인)·`/user/queue/chats/notifications`(방을 열지 않은 연결의 방별 새 메시지 알림)·
        `/user/queue/chats/sync`(재동기화)·`/user/queue/chats/errors`(오류). 형식은 resync-frame 계약을 따른다.
        보내는 본문(`content`)은 공백만으로 이뤄질 수 없고 2,000자 이하다. 넘으면 오류 큐로 INVALID_REQUEST가 `clientMessageId`와 함께 온다.
        보낼 때 `clientMessageId`(선택, 64자 이하)를 붙이면 보낸 사람에게 전달되는 메시지와 오류에 그대로 돌려준다.
        같은 참여 안에서 같은 `clientMessageId`로 다시 보내면 새로 저장하지 않고 기존 메시지를 보낸 사람에게만 다시 보낸다(알림 없음, 본문이 달라도 기존 메시지).
        나갔다가 재입장한 뒤에는 이전에 쓴 `clientMessageId`로 보내도 새 메시지로 저장한다.
        `clientMessageId`는 보낸 사람 본인 메시지에만 담긴다(보낸 사람 연결의 방 topic 프레임·전송 결과 확인·메시지 이력·방 목록의 마지막 메시지).
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
            `lastMessage`는 내가 볼 수 있는 가장 최근 메시지(없으면 null), `unreadCount`는 읽음 처리 이후 온 상대 메시지 수다.
            1:1 방(`roomType: DIRECT`)은 `members`에 두 참여자를 싣고 `group`은 null이다.
            모집용 단체방(`roomType: GROUP`)은 사용 중(ACTIVE)·일시 중지(SUSPENDED)와 조회 만료 전의 읽기 전용(READ_ONLY) 방이 나온다.
            단체방은 `members`가 빈 목록이고, `group`에 모집글 ID·호스트 ID(왕관 표시)·제목·읽기 전용 사유·전환 시각·조회 만료 시각·
            인원(호스트 포함)·게스트 정원·내 참여 세대(`myParticipationVersion`)를 싣는다. 참여자는 참여자 페이지로 읽는다.""")
    RsData<List<ChatRoomResponse>> getMyChatRooms(Long userId);

    @Operation(summary = "채팅방 상세", description = """
            참여 중인 방 하나를 방 목록의 한 항목과 같은 형식으로 조회한다.
            단체방은 조회 만료 시각(`group.historyExpiresAt`)에 도달하면 볼 수 없다(404).
            오류: 403 `CHAT_NOT_ALLOWED`, 403 `NOT_CHAT_ROOM_MEMBER`(참여 중이 아님), 404 `CHAT_ROOM_NOT_FOUND`(없음·종료·조회 만료).""")
    RsData<ChatRoomResponse> getChatRoom(Long userId, Long chatRoomId);

    @Operation(summary = "참여자 페이지", description = """
            방의 현재 참여자를 참여 순서로 나눠 조회한다. 다음 페이지는 응답의 `nextCursor`를 `cursor`로 보낸다(null이면 마지막).
            각 참여자의 `participationVersion`은 강퇴 요청에 쓴다. 단체방 목록·상세는 참여자를 싣지 않으므로 이 API로 읽는다.
            오류: 403 `CHAT_NOT_ALLOWED`, 403 `NOT_CHAT_ROOM_MEMBER`, 404 `CHAT_ROOM_NOT_FOUND`(없음·종료·조회 만료).""")
    RsData<ChatRoomMemberPageResponse> getMembers(Long userId, Long chatRoomId,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 처음부터") Long cursor,
            @Parameter(description = "페이지 크기 (기본 50)")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = MAX_PAGE_SIZE, message = "size는 " + MAX_PAGE_SIZE + " 이하여야 합니다.") int size);

    @Operation(summary = "메시지 이력 조회", description = """
            최신순으로 조회한다. 본인이 볼 수 있는 범위(재입장 이후)의 메시지만 나온다.
            단체방은 읽기 전용·일시 중지 방도 조회 만료 시각 전까지 조회한다(그 시각부터 404 `CHAT_ROOM_NOT_FOUND`).
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
            `participationVersion`은 메시지 이력 응답의 값을 쓴다. 회원은 로그인 정보로 정한다.
            응답의 `lastReceivedSequence`는 반영 뒤 서버의 회원 수신 위치다. 다른 기기가 더 높게 올렸으면 요청 값보다 클 수 있다.
            같은 값·낮은 값은 바꾸지 않고 현재 값으로 200이다(멱등). 이 값은 "적어도 한 기기가 확인한 위치"라 기기의 확인 위치로 복사하지 않는다.
            읽음 처리·안 읽은 수와 독립이다.
            오류: 400 `INVALID_REQUEST`(필드 누락·음수, `participationVersion` 1 미만), 400 `RECEIVED_SEQUENCE_OUT_OF_RANGE`(조회 시작점보다 작거나 방의 마지막 순번 초과, 재동기화 뒤 다시 계산),
            403 `CHAT_NOT_ALLOWED`, 403 `NOT_CHAT_ROOM_MEMBER`(참여 중이 아님), 404 `CHAT_ROOM_NOT_FOUND`(방 없음·종료),
            409 `STALE_PARTICIPATION`(현재 참여 세대가 아님, 이전 세대의 캐시·확인 위치를 버리고 방 목록과 이력을 다시 동기화).""")
    RsData<ChatReceiptResponse> markReceived(Long userId, Long chatRoomId, @Valid ReceivedChatRoomRequest request);

    @Operation(summary = "채팅방 나가기", description = """
            남은 참여자에게 퇴장 안내가 실시간으로 가고, 마지막 참여자가 나가면 방이 종료된다.
            이미 나갔어도 204로 응답한다. 강등·정지된 회원도 나갈 수 있다.
            단체방: 모두 나가도 방이 종료되지 않는다. 사용 중·일시 중지 방에서는 남은 참여자에게 나가기 안내(`GROUP_MEMBER_LEFT`)가 가고,
            읽기 전용 방에서는 안내를 남기지 않는다. 호스트는 사용 중·일시 중지 방을 나갈 수 없고(409 `HOST_CANNOT_LEAVE`, 대신 폐쇄)
            직접 폐쇄한 방만 나갈 수 있다. 나간 뒤에는 호스트가 다시 초대해야만 들어오고 이전 메시지는 보이지 않는다.
            조회 만료된 단체방은 404 `CHAT_ROOM_NOT_FOUND`다.""")
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

    @Operation(summary = "웹소켓 연결 티켓 발급", description = """
            웹소켓(STOMP) 연결에 쓰는 일회용 티켓을 받는다. 인플루언서 이상 또는 관리자만 쓸 수 있다.
            브라우저는 웹소켓 업그레이드 요청에 Authorization을 싣지 못하므로, 이 API를 access token(Bearer)으로 부른 뒤
            `/api/v1/chats/ws?ticket={ticket}`로 연결한다. 티켓은 `expiresIn`초(기본 30초) 안에 한 번만 쓸 수 있다.
            다시 연결할 때마다 새로 받는다. 티켓이 없거나 만료됐거나 이미 썼으면 핸드셰이크가 401이다(브라우저에는 1006).
            연결은 `connectionExpiresAt`(access token의 만료 시각, 최대 발급 뒤 15분, ISO-8601 UTC)에 `4401 TOKEN_EXPIRED`로
            닫힌다. 연결을 유지하려면 그 전에 새 티켓을 받아 열린 연결에 `SEND /pub/chats/reauth`(헤더 `ticket`)로 보낸다.
            결과는 `/user/queue/chats/connection`으로 그 연결에만 온다.
            회원마다 60초에 10번까지 받을 수 있다. 넘으면 429(TOO_MANY_TICKET_REQUESTS).
            티켓을 저장할 수 없으면 503(CONNECTION_TICKET_UNAVAILABLE).""")
    RsData<ChatConnectionTicketResponse> issueConnectionTicket(Long userId,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true) String tokenExpiresAt);
}
