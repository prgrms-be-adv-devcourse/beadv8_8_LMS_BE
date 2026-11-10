package com.hapbang.auxiliary.chatbot.out;

import tools.jackson.databind.json.JsonMapper;

/** jsonb 컬럼에 넣을 JSON 문자열을 만든다 (#717). 사본 Store들이 같이 쓴다 */
final class JsonColumns {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JsonColumns() {
    }

    static String write(Object value) {
        return JSON.writeValueAsString(value);
    }
}
