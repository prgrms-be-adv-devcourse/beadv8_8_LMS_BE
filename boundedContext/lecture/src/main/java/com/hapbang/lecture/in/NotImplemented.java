package com.hapbang.lecture.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 명세(Swagger)만 먼저 공개한 API의 본문. 구현되면 이 클래스는 지운다.
 */
final class NotImplemented {

    private NotImplemented() {
    }

    static ResponseStatusException yet() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "아직 구현되지 않은 API입니다.");
    }
}
