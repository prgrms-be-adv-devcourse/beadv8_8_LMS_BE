package com.hapbang.shared.member.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import tools.jackson.databind.json.JsonMapper;

class UserDtoTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("옛 생성자로 만들면 분야는 빈 목록, 자기소개는 null이다")
    void oldConstructors() {
        UserDto withVersion = new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L);
        UserDto withoutVersion = new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE);

        assertThat(withVersion.contentCategories()).isEmpty();
        assertThat(withVersion.bio()).isNull();
        assertThat(withoutVersion.contentCategories()).isEmpty();
        assertThat(withoutVersion.bio()).isNull();
    }

    @Test
    @DisplayName("분야가 null이면 빈 목록이다")
    void nullCategories() {
        UserDto dto = new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L, null, null);

        assertThat(dto.contentCategories()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("받은 분야 목록을 복사해, 원본이 바뀌어도 그대로이고 고른 순서를 지킨다. null 코드는 받지 않는다")
    void copiesCategories() {
        List<String> source = new ArrayList<>(List.of("MAPLESTORY", "GAME"));
        UserDto dto = new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L, source, "소개");
        source.clear();

        assertThat(dto.contentCategories()).containsExactly("MAPLESTORY", "GAME");
        assertThatThrownBy(() -> dto.contentCategories().add("MUKBANG"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L,
                Arrays.asList("GAME", null), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("새 필드가 없는 옛 JSON을 읽으면 분야는 빈 목록, 자기소개는 null이다")
    void readsOldJson() {
        String oldJson = """
                {"id":1,"nickname":"닉네임","role":"INFLUENCER","status":"ACTIVE","version":3}
                """;

        UserDto dto = jsonMapper.readValue(oldJson, UserDto.class);

        assertThat(dto).isEqualTo(new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L));
    }

    @Test
    @DisplayName("분야·자기소개를 JSON으로 썼다가 읽어도 같다. 분야는 enum이 아니라 처음 보는 코드도 읽는다")
    void roundTrip() {
        UserDto dto = new UserDto(1L, "닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 3L,
                List.of("GAME", "NEW_CATEGORY_FROM_FUTURE"), "소개");

        String json = jsonMapper.writeValueAsString(dto);

        assertThat(json).contains("\"contentCategories\":[\"GAME\",\"NEW_CATEGORY_FROM_FUTURE\"]", "\"bio\":\"소개\"");
        assertThat(jsonMapper.readValue(json, UserDto.class)).isEqualTo(dto);
    }
}
