package com.hapbang.customersupport.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;

/**
 * 이의 제기 판단·철회 요청 내용의 지문 (#279, D60 (2)). 같은 요청이 다시 오면 처리 중(202)·완료로 돌려주고,
 * 다른 내용이면 충돌(409)로 본다. 판단은 인정 여부·판단 안내 답변·관리자, 철회는 구매자다.
 * 명령 종류와 값들을 {@code |}로 이은 문자열의 SHA-256이다. 값의 순서를 바꾸면 처리 중인 요청과 비교할 수 없다.
 */
final class ObjectionCommandHash {

    private ObjectionCommandHash() {
    }

    static String resolution(boolean accepted, String replyContent, Long adminUserId) {
        return of(LectureObjectionCommandType.RESOLVE, accepted, replyContent.strip(), adminUserId);
    }

    static String withdrawal(Long buyerUserId) {
        return of(LectureObjectionCommandType.WITHDRAW, buyerUserId);
    }

    private static String of(LectureObjectionCommandType type, Object... values) {
        String payload = Stream.concat(Stream.of(type.name()), Stream.of(values).map(String::valueOf))
                .collect(Collectors.joining("|"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
