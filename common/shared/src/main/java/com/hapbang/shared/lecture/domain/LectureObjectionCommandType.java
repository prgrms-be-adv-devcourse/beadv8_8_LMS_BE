package com.hapbang.shared.lecture.domain;

/**
 * 강의 내용 다름 이의 제기 명령의 종류 (#278, D60·D71). 고객센터가 명령 ID를 만들고({@link #commandIdOf}),
 * 강의는 명령 이력으로 같은 명령을 한 번만 처리한다. 상수 이름은 명령 ID·이벤트 본문 문자열이므로 바꾸지 않는다.
 */
public enum LectureObjectionCommandType {
    /** 접수. 고객센터가 강의 내부 HTTP API로 보낸다 */
    FILE,
    /** 판단. 고객센터가 이벤트로 보낸다 */
    RESOLVE,
    /** 철회. 고객센터가 이벤트로 보낸다 */
    WITHDRAW;

    /** 문의 하나에 종류마다 하나인 명령 ID (예: {@code FILE:12}). 실패한 명령의 운영 복구(#267)는 다른 ID를 쓴다 */
    public String commandIdOf(long inquiryId) {
        return name() + ":" + inquiryId;
    }
}
