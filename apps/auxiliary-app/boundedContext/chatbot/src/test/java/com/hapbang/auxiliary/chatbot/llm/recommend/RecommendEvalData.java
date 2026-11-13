package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 평가 더미 데이터(#867). {@link RecommendEvalDataGenerator}가 시드로 만들고 {@link #RESOURCE}로 커밋한다.
 * 실제 데이터·이름·이메일은 없다(사본 테이블에도 그런 열이 없다).
 * <p>
 * ID 구간: 강의 회원 1~100, 판매자 1001~, 인플루언서 2001~, 강의 1~. 숨긴 정답과 80:20 나누기도 이 파일에 함께 둔다(같은 입력이면 같은 숫자).
 *
 * @param lectureAnswers    강의 회원마다 숨긴 구매(정답 강의)
 * @param influencerAnswers 인플루언서마다 숨긴 함께 완료한 상대(정답 인플루언서)
 * @param lectureSplit      강의 회원의 조정용 80명·확인용 20명
 * @param influencerSplit   인플루언서의 조정용 80명·확인용 20명
 */
public record RecommendEvalData(
        long seed,
        List<Field> fields,
        List<Long> sellerIds,
        List<Lecture> lectures,
        List<Member> members,
        List<Activity> purchases,
        List<Activity> carts,
        List<Rating> lectureReviews,
        List<Influencer> influencers,
        List<Appearance> appearances,
        List<AppearanceReview> appearanceReviews,
        List<Answer> lectureAnswers,
        List<Answer> influencerAnswers,
        Split lectureSplit,
        Split influencerSplit
) {

    /** 클래스패스 위치 */
    public static final String RESOURCE = "chatbot/recommend-eval/dataset.json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 분야. 강의 사본에는 분야 열이 없어 강의 글로만 드러난다 */
    public record Field(String code, String name) {
    }

    public record Lecture(
            long id,
            long sellerId,
            String field,
            String title,
            String description,
            List<String> contentTitles
    ) {
    }

    /**
     * @param tasteFields 취향 분야 코드(1~2개)
     * @param interest    관심사 문장(취향 분야 하나에 대한 것)
     */
    public record Member(long id, List<String> tasteFields, String interest) {
    }

    /**
     * 구매(OWNED) 또는 장바구니.
     *
     * @param taste 취향 분야에서 고른 신호인지(false면 잡음). 추천 계산에는 쓰지 않고 생성 규칙 확인용이다
     */
    public record Activity(
            long id,
            long memberId,
            long lectureId,
            boolean taste,
            LocalDateTime at
    ) {
    }

    public record Rating(long id, long memberId, long lectureId, int rating) {
    }

    /** @param fields 분야 코드(1~2개) */
    public record Influencer(long id, List<String> fields, String bio) {
    }

    /** 함께 마친 출연 */
    public record Appearance(long id, long hostId, long guestId) {
    }

    public record AppearanceReview(long id, long appearanceId, long revieweeId, int rating) {
    }

    /** @param requesterId 추천을 받는 회원, answerId 숨긴 정답(강의 ID 또는 인플루언서 ID) */
    public record Answer(long requesterId, long answerId) {
    }

    public record Split(List<Long> tuning, List<Long> holdout) {
    }

    /** 함께 출연한 두 회원(순서 없음: 작은 ID가 앞) */
    record Partners(long low, long high) {

        static Partners of(long a, long b) {
            return new Partners(Math.min(a, b), Math.max(a, b));
        }
    }

    public static RecommendEvalData read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return JSON.readValue(in, RecommendEvalData.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 분야 코드 → 이름 */
    public Map<String, String> fieldNames() {
        Map<String, String> names = new HashMap<>();
        for (Field field : fields) {
            names.put(field.code(), field.name());
        }
        return names;
    }

    /** 요청 회원 ID → 정답 ID */
    public static Map<Long, Long> byRequester(List<Answer> answers) {
        Map<Long, Long> map = new HashMap<>();
        for (Answer answer : answers) {
            map.put(answer.requesterId(), answer.answerId());
        }
        return map;
    }

    /** 숨긴 행을 뺀 같은 데이터 */
    RecommendEvalData withRows(List<Activity> purchases, List<Activity> carts, List<Rating> lectureReviews,
            List<Appearance> appearances, List<AppearanceReview> appearanceReviews) {
        return new RecommendEvalData(seed, fields, sellerIds, lectures, members, purchases, carts, lectureReviews, influencers,
                appearances, appearanceReviews, lectureAnswers, influencerAnswers, lectureSplit, influencerSplit);
    }

    /**
     * 커밋할 JSON. 목록은 한 줄에 한 항목이라 바뀐 줄만 diff에 보인다.
     */
    public String toJson() {
        List<String> parts = new ArrayList<>();
        for (RecordComponent component : RecommendEvalData.class.getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(this);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            String name = JSON.writeValueAsString(component.getName());
            if (value instanceof List<?> list) {
                List<String> items = new ArrayList<>();
                for (Object item : list) {
                    items.add("  " + JSON.writeValueAsString(item));
                }
                parts.add(name + ": [\n" + String.join(",\n", items) + "\n]");
            } else {
                parts.add(name + ": " + JSON.writeValueAsString(value));
            }
        }
        return "{\n" + String.join(",\n", parts) + "\n}\n";
    }
}
