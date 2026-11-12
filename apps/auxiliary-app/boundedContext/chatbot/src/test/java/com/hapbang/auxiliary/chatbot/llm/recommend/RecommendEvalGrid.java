package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 시험할 가중치 조합(#867). 환경 변수로 받은 값을 운영과 같은 설정 record에 묶는다(Spring {@link Binder}, 같은 기본값·검증).
 * 값마다 앱을 다시 띄우지 않고 새 record로 추천 UseCase를 만든다.
 * <ul>
 *     <li>{@value #GRID_ENV}: 바꿔 볼 값. {@code 키=값,값;키=값,값}. 키는 1~2개, 2개면 모든 조합을 돈다.</li>
 *     <li>{@value #BASE_ENV}: 채택해 고정할 값. {@code 키=값;키=값}.</li>
 *     <li>키는 {@code hapbang.chatbot.recommend.} 뒤의 이름이다(예: {@code lecture.popularity-weight}). 보정 대상 키만 받는다
 *     (오타가 조용히 무시되지 않게). 결과 수({@code result-size}·{@code size})는 평가가 5와 20으로 정한다.</li>
 *     <li>둘 다 없으면 지금 설정값 그대로(기준선).</li>
 * </ul>
 */
final class RecommendEvalGrid {

    static final String GRID_ENV = "RECOMMEND_EVAL_GRID";
    static final String BASE_ENV = "RECOMMEND_EVAL_BASE";

    static final String PREFIX = "hapbang.chatbot.recommend.";
    static final String LECTURE = "lecture.";
    static final String INFLUENCER = "influencer.";

    /** 보정 대상 키(이슈 #867 "가중치 보정"의 대상) */
    static final Set<String> KEYS = Set.of(
            "lecture.weights.owned", "lecture.weights.cart", "lecture.weights.rating1", "lecture.weights.rating2",
            "lecture.weights.rating3", "lecture.weights.rating4", "lecture.weights.rating5", "lecture.signal-limit",
            "lecture.interest-weight", "lecture.popularity-weight",
            "influencer.partner-weight", "influencer.popularity-weight", "influencer.popularity.completed-weight",
            "influencer.popularity.rating-weight", "influencer.popularity.prior-reviews");

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final List<ConfigurationPropertySource> base;

    /** @param base 지금 설정값(평가에서는 테스트 컨텍스트의 Environment) */
    RecommendEvalGrid(Iterable<ConfigurationPropertySource> base) {
        this.base = new ArrayList<>();
        base.forEach(this.base::add);
    }

    /**
     * 조합 하나.
     *
     * @param label     표에 찍을 바꾼 값(기준선이면 "기준선")
     * @param overrides 키 → 값(BASE와 이 조합의 GRID 값)
     * @param lecture   강의 평가를 돌릴지
     * @param influencer 인플루언서 평가를 돌릴지
     */
    record Combo(String label, Map<String, String> overrides, boolean lecture, boolean influencer) {
    }

    /**
     * @param grid 바꿔 볼 값. 없으면 기준선 한 줄
     * @param base 고정할 값
     * @throws IllegalArgumentException 형식이 틀리거나, 모르는 키거나, 키가 3개 이상일 때
     */
    static List<Combo> combos(@Nullable String grid, @Nullable String base) {
        Map<String, String> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : parse(base, BASE_ENV).entrySet()) {
            if (entry.getValue().size() != 1) {
                throw new IllegalArgumentException(BASE_ENV + "에는 키마다 값 하나만 줍니다: " + entry.getKey());
            }
            fixed.put(entry.getKey(), entry.getValue().getFirst());
        }
        Map<String, List<String>> axes = parse(grid, GRID_ENV);
        if (axes.size() > 2) {
            throw new IllegalArgumentException(GRID_ENV + "는 한 번에 키 1~2개만 바꿉니다(한두 값씩 보정)");
        }
        if (axes.isEmpty()) {
            return List.of(new Combo(fixed.isEmpty() ? "기준선" : "고정값", fixed, true, true));
        }
        boolean lecture = false;
        boolean influencer = false;
        for (String key : axes.keySet()) {
            lecture |= key.startsWith(LECTURE);
            influencer |= key.startsWith(INFLUENCER);
        }
        List<Combo> combos = new ArrayList<>();
        combine(new ArrayList<>(axes.entrySet()), 0, new LinkedHashMap<>(), fixed, lecture, influencer, combos);
        return combos;
    }

    private static void combine(List<Map.Entry<String, List<String>>> axes, int index, Map<String, String> chosen,
            Map<String, String> fixed, boolean lecture, boolean influencer, List<Combo> combos) {
        if (index == axes.size()) {
            Map<String, String> overrides = new LinkedHashMap<>(fixed);
            overrides.putAll(chosen);
            List<String> label = new ArrayList<>();
            for (Map.Entry<String, String> entry : chosen.entrySet()) {
                label.add(entry.getKey() + "=" + entry.getValue());
            }
            combos.add(new Combo(String.join(", ", label), overrides, lecture, influencer));
            return;
        }
        Map.Entry<String, List<String>> axis = axes.get(index);
        for (String value : axis.getValue()) {
            chosen.put(axis.getKey(), value);
            combine(axes, index + 1, chosen, fixed, lecture, influencer, combos);
        }
        chosen.remove(axis.getKey());
    }

    private static Map<String, List<String>> parse(@Nullable String text, String name) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return parsed;
        }
        for (String part : text.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(name + " 형식은 키=값,값;키=값 입니다: " + part);
            }
            String key = part.substring(0, equals).strip();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(name + "에 모르는 키가 있습니다: " + key + " (받는 키: " + KEYS + ")");
            }
            List<String> values = new ArrayList<>();
            for (String value : part.substring(equals + 1).split(",")) {
                if (!value.isBlank()) {
                    values.add(value.strip());
                }
            }
            if (values.isEmpty() || parsed.containsKey(key)) {
                throw new IllegalArgumentException(name + "의 키에 값이 없거나 키가 겹칩니다: " + key);
            }
            parsed.put(key, values);
        }
        return parsed;
    }

    /** 이 조합의 강의 설정. 결과 수는 {@code resultSize} */
    ChatbotLectureRecommendProperties lecture(Combo combo, int resultSize) {
        return bind(combo, "lecture", "lecture.result-size", resultSize, ChatbotLectureRecommendProperties.class);
    }

    /** 이 조합의 인플루언서 설정. 결과 수는 {@code size} */
    ChatbotInfluencerRecommendProperties influencer(Combo combo, int size) {
        return bind(combo, "influencer", "influencer.size", size, ChatbotInfluencerRecommendProperties.class);
    }

    private <T> T bind(Combo combo, String name, String sizeKey, int size, Class<T> type) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : combo.overrides().entrySet()) {
            overrides.put(PREFIX + entry.getKey(), entry.getValue());
        }
        overrides.put(PREFIX + sizeKey, size);
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        sources.add(new MapConfigurationPropertySource(overrides));
        sources.addAll(base);
        T properties = new Binder(sources).bindOrCreate(PREFIX + name, type);
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(properties);
        if (!violations.isEmpty()) {
            List<String> messages = new ArrayList<>();
            for (ConstraintViolation<T> violation : violations) {
                messages.add(violation.getPropertyPath() + " " + violation.getMessage());
            }
            throw new IllegalArgumentException("설정값이 범위를 벗어났습니다 " + combo.label() + ": " + messages);
        }
        return properties;
    }
}
