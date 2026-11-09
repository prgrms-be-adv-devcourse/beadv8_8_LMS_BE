package com.hapbang.auxiliary.chatbot.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사본 값의 해시 (#716). 싱크가 원본 값과 우리 값을 비교할 때 쓴다. 계산은 이 클래스 한 곳에서만 한다.
 * <ul>
 *     <li><b>받은 DTO로 계산한다.</b> 저장한 컬럼으로 다시 만들지 않는다. 사본 클래스가 DTO에서 비교할 값만 골라 넘긴다.</li>
 *     <li>version은 넣지 않는다. version만 오르고 값이 같으면(원본이 DTO 밖 필드만 바꿈) 해시가 같아 정상으로 본다.</li>
 *     <li>시각은 넘기기 전에 {@link CopyTimes}로 KST·마이크로초로 맞춘다.</li>
 *     <li>필드 이름 순으로 정렬한 JSON(null도 씀, 목록은 받은 순서 그대로)의 SHA-256을 16진수 64자로 낸다.</li>
 * </ul>
 */
public final class CopyHash {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // record는 생성자 인자(선언 순서)를 먼저 쓰므로 이것도 꺼야 이름 순이 된다
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private CopyHash() {
    }

    /** 값(레코드)의 해시 */
    public static String of(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    /** 해시에 넣는 JSON. 테스트가 정렬·형식을 확인한다 */
    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
