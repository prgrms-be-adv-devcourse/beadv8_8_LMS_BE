# 계약 (a): 연결 제어 작업 기록 (#417 → #418)

회원 반영 결과가 채팅 불가일 때 연결 재검사 작업을 남기는 계약이다. 설계는 [설계 문서](../final-design.md) 5.3, 테이블은 [테이블 계약](tables-and-migrations.md)이다.

## 소유

- 인터페이스, 제어 outbox·발행 체크포인트 테이블과 기록 구현은 **#418이 소유**한다. [공통 골격](parallel-work.md#공통-골격-동결)으로 #417·#418·#419에 같은 내용으로 넣고 고치지 않는다.
- #417은 인터페이스를 호출만 한다. 적재·발행·ACK·회수·재구성은 #418, 제어 채널 수신과 로컬 연결 종료는 #419다.

## 인터페이스

```java
package com.hapbang.chat.app;

import java.time.Instant;

public interface ChatConnectionControl {

    /**
     * 회원의 로컬 연결 재검사 작업(RECHECK_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 같은 (회원, 원본 버전, 종류)는 한 번만 기록하고, 이미 있으면 아무것도 하지 않는다.
     *
     * @param memberVersion 반영한 회원 원본 버전. 버전 없는 회원 정보면 null
     */
    void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason);

    /**
     * 회원 로그아웃에 따른 로컬 연결 전체 종료 작업(LOGOUT_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 호출마다 한 행을 남긴다(중복 식별 없음).
     *
     * @param loggedOutAt 이 시각보다 먼저 인증한 연결만 닫는다(#548)
     */
    void requestLogout(Long userId, Instant loggedOutAt);
}
```

- `ChatControlReason`: `MEMBER_MODIFIED`(Kafka 회원 변경 이벤트), `MEMBER_BACKFILL`(5.4 백필), `MEMBER_LOGGED_OUT`(Kafka 회원 로그아웃 이벤트, #501).
- `ChatControlType`: `RECHECK_USER`(최신 자격을 다시 보고 채팅 불가면 `1008 CHAT_NOT_ALLOWED`로 닫는다), `LOGOUT_USER`(자격과 관계없이 그 회원의 로컬 연결을 모두 `1008 LOGGED_OUT`으로 닫는다, 설계 문서 6장·Q8, #501).
- 로그아웃 기록(#501)은 같은 테이블·체크포인트·Stream·채널을 쓰고, repository의 native `INSERT`(충돌 처리 없음)로 넣는다. 기준 시각은 `logged_out_at`에 남기고 제어 채널 payload의 `loggedOutAt`으로 나간다(#548). 호출하는 쪽(`DefaultChatUserSyncService`)이 이벤트의 `loggedOutAt`을 처리 시각을 넘지 않게 줄이고, 없으면 처리 시각으로 대신한다. 같은 로그아웃 이벤트가 다시 처리돼 행이 늘어도 연결을 다시 닫을 뿐이다. 로그아웃 작업을 놓치면 재검사로 회복되지 않지만, 같은 처리에서 남긴 Redis 로그아웃 표식으로 하트비트가 약 30초 안에 닫고, Redis 장애면 연결 만료가 닫는다(설계 문서 6장).
- 구현 `out/JpaChatConnectionControl`은 `@Transactional(propagation = MANDATORY)`다. 트랜잭션 없이 부르면 예외다. 제어 outbox는 repository의 native `INSERT ... ON CONFLICT DO NOTHING`으로, 발행 체크포인트는 JPA 엔티티 저장으로 넣는다.
- 기록은 `chat_control_outbox` 한 행(`PENDING`, 새 `controlId`)과 `chat_publish_checkpoint` 한 행(그룹 `chat-control-v1`, `PENDING`)을 같은 트랜잭션에 넣는다. 중복은 `ON CONFLICT DO NOTHING`으로 흡수해 호출한 트랜잭션을 rollback-only로 만들지 않는다.

## 호출 규칙 (#417)

- **기존 복제본을 갱신**(Kafka 변경 이벤트·백필 갱신)한 같은 트랜잭션에서, **이번에 반영한 최신 상태가 채팅 불가**이면 호출한다. 기존 `lostChat`(가능 → 불가 전이)만으로 판단하지 않는다.
- 복제본을 새로 만든 경우(가입 이벤트·백필 생성)는 호출하지 않는다. 복제본이 없던 회원은 STOMP 연결 검사(`canChat`)를 통과하지 못해 닫을 연결이 없다.
- 옛 버전이라 반영을 건너뛴 경우에는 호출하지 않는다. 이미 같은 버전의 작업이 있으면 구현이 중복을 흡수한다. 버전 없는(null) 정보의 반복 변경처럼 중복으로 흡수된 경우는 #419의 주기적 재검사가 회복한다.
- 회원 반영이 롤백되면 제어 작업도 남지 않는다. Kafka offset은 이 커밋 뒤에 처리한다.
- `ChatWebSocketSessionRegistry.closeByUserId`를 회원 이벤트 리스너에서 직접 부르지 않는다. 연결 종료는 제어 작업 → #418 → #419 경로로 한다.

## 이후 처리 (#418·#419)

- #418 relay가 `chat_control_outbox`를 `<namespace>:chat:control:stream:v1`에 `XADD`하고, 그룹 `chat-control-v1` consumer가 `RealtimeChatFanout.publishControl`을 부른 뒤 체크포인트 `PUBLISHED` → `XACK`한다.
- #419 구독자는 자기 chat DB의 최신 회원 상태를 확인해 지금도 채팅 불가일 때만 그 회원의 로컬 연결을 `CHAT_NOT_ALLOWED`로 닫는다. 옛 명령으로 자격이 복구된 회원을 닫지 않는다.
- `LOGOUT_USER`는 구독자(`ChatRealtimeFacade.applyControl`)가 자격을 보지 않고 그 회원의 로컬 연결 중 `loggedOutAt`보다 먼저 인증한 연결을 `LOGGED_OUT`으로 닫는다(#501, #548). `loggedOutAt`이 없는 옛 작업은 그 회원의 연결을 모두 닫는다.
