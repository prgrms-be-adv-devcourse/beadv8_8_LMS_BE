package com.hapbang.coupon.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

// 예약·해제 작업 기록의 내용 키 정규화를 본다
class UsageContentKeyTest {

    private static String reserveKey(List<String> items) {
        return UsageContentKey.of(100, 55, items);
    }

    @Test
    void 키는_회원과_주문_뒤에_정규화한_요청의_SHA256을_붙인다() {
        String key = reserveKey(List.of(UsageContentKey.part("i1", List.of("c1"))));

        assertThat(key).matches("m100\\|o55\\|[0-9a-f]{64}");
    }

    @Test
    void 항목과_쿠폰의_순서가_달라도_같은_키다() {
        String key = reserveKey(List.of(
                UsageContentKey.part("i1|l7", List.of("c1|g0", "c2|g3")),
                UsageContentKey.part("i2|l8", List.of("c3|g1"))));
        String reordered = reserveKey(List.of(
                UsageContentKey.part("i2|l8", List.of("c3|g1")),
                UsageContentKey.part("i1|l7", List.of("c2|g3", "c1|g0"))));

        assertThat(reordered).isEqualTo(key);
    }

    @Test
    void 내용이나_회원_주문이_다르면_다른_키다() {
        List<String> items = List.of(UsageContentKey.part("i1", List.of("c1|d1000")));

        String key = reserveKey(items);

        assertThat(reserveKey(List.of(UsageContentKey.part("i1", List.of("c1|d900"))))).isNotEqualTo(key);
        assertThat(UsageContentKey.of(101, 55, items)).isNotEqualTo(key);
        assertThat(UsageContentKey.of(100, 56, items)).isNotEqualTo(key);
        // 같은 조각이 두 번이면 한 번과 다르다
        assertThat(reserveKey(List.of(items.getFirst(), items.getFirst()))).isNotEqualTo(key);
    }

    @Test
    void 값_안의_구분_문자로_다른_요청과_섞이지_않는다() {
        String split = reserveKey(List.of(UsageContentKey.part("i1", List.of("a", "b"))));
        String joined = reserveKey(List.of(UsageContentKey.part("i1", List.of("1:a1:b"))));
        String moved = reserveKey(List.of(UsageContentKey.part("i1a", List.of("b"))));

        assertThat(split).isNotEqualTo(joined).isNotEqualTo(moved);
        assertThat(reserveKey(List.of("ab"))).isNotEqualTo(reserveKey(List.of("a", "b")));
    }
}
