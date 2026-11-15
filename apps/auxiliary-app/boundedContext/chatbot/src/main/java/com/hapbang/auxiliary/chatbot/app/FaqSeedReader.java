package com.hapbang.auxiliary.chatbot.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 초기 FAQ 원본을 읽는다(#715). 고객센터에 FAQ 기능이 없어 이 파일이 원본이다. 원본 문서는 FAQ 53건·분류 11개·정책 근거로 만들었다.
 * 파일이 깨졌으면(필수 값 없음, code 중복) 예외를 던져 시드 맞추기를 하지 않는다. DB를 지우는 일이 없게 한다.
 */
@Component
public class FaqSeedReader {

    static final String LOCATION = "chatbot/faq-seed.json";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<FaqSeed> read() {
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            List<FaqSeed> seeds = jsonMapper.readValue(in, new TypeReference<List<FaqSeed>>() {
            });
            validate(seeds);
            return seeds;
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 시드 파일을 읽지 못했습니다: " + LOCATION, e);
        }
    }

    private static void validate(List<FaqSeed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalStateException("FAQ 시드가 비어 있습니다");
        }
        Set<String> codes = new HashSet<>();
        for (FaqSeed seed : seeds) {
            if (isBlank(seed.code()) || seed.category() == null || isBlank(seed.question()) || isBlank(seed.answer())
                    || isBlank(seed.policyRef())) {
                throw new IllegalStateException("FAQ 시드에 빈 값이 있습니다 code=" + seed.code());
            }
            if (!codes.add(seed.code())) {
                throw new IllegalStateException("FAQ 시드 code가 겹칩니다 code=" + seed.code());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
