package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 원본 정보를 채팅 회원 복제본({@code ChatUser})에 반영한다. 메서드마다 하나의 로컬 트랜잭션에서 회원 반영과
 * 필요한 연결 제어 작업 기록을 함께 처리한다(설계 문서 5.3·5.4, 계약 {@code docs/contracts/control-recording.md}).
 * <ul>
 *     <li>가입: 생성만 한다. 이미 있으면 건너뛴다.</li>
 *     <li>변경: 기존 복제본만 회원 버전이 더 클 때 갱신한다. 복제본이 없으면 만들지 않고 실패한다.</li>
 *     <li>백필: 회원 원본 스냅샷으로 없으면 만들고, 있으면 변경과 같은 버전 규칙으로 갱신한다. 가입 이벤트 소비로 기록하지 않는다.</li>
 * </ul>
 * 기존 복제본을 갱신한 결과가 채팅 불가이면 같은 트랜잭션에 연결 재검사 작업({@link ChatConnectionControl})을 남긴다.
 * 로그아웃은 복제본을 바꾸지 않고 연결 종료 작업만 남긴다.
 */
public interface ChatUserSyncService {

    /** 회원 가입 정보로 복제본을 만든다. 이미 있으면 {@code SKIPPED_EXISTING} */
    ChatUserSyncResult syncJoined(UserDto user);

    /**
     * 회원 변경 정보를 기존 복제본에 반영한다.
     *
     * @throws com.hapbang.chat.app.exception.ChatException 복제본이 없으면 {@code CHAT_USER_NOT_FOUND}(생성하지 않는다)
     */
    ChatUserSyncResult syncModified(UserDto user);

    /**
     * 백필용 회원 원본 스냅샷을 반영한다. 회원마다 짧은 트랜잭션을 새로 열고, 가입·변경 이벤트와 동시에 생성·갱신해 충돌하면
     * 다시 읽어 제한된 횟수만큼 다시 시도한다. 호출한 쪽의 트랜잭션에 합류하지 않는다.
     */
    ChatUserSyncResult syncBackfill(UserDto user);

    /**
     * 회원 로그아웃을 반영한다: 그 회원의 모든 로컬 연결을 닫는 작업({@link ChatConnectionControl#requestLogout})을 한 트랜잭션에
     * 남기고, 커밋 뒤 로그아웃 표식({@link ChatLogoutMarkers})을 남긴다. 복제본은 바꾸지 않는다. 연결 종료는 그 작업이 제어 Stream·
     * 제어 채널을 거쳐 처리하고, 놓치면 하트비트가 표식으로 닫는다(설계 문서 6장·Q8).
     *
     * @throws com.hapbang.chat.app.exception.ChatException 회원 ID가 없거나 1보다 작으면 {@code INVALID_REQUEST}
     */
    void syncLoggedOut(Long userId);
}
