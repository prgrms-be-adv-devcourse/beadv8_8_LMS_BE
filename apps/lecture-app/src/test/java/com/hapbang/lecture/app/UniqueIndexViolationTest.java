package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.PostgresErrorFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UniqueIndexViolationTest {

    private static final String INDEX = "uk_lecture_review_order_item";
    private static final String OTHER_INDEX = "uk_lecture_review_lecture_reviewer";

    @Test
    void 지정한_인덱스의_UNIQUE_위반이면_참이다() {
        assertThat(UniqueIndexViolation.isOf(violation(UNIQUE_VIOLATION, INDEX), INDEX, OTHER_INDEX)).isTrue();
        assertThat(UniqueIndexViolation.isOf(violation(UNIQUE_VIOLATION, OTHER_INDEX), INDEX, OTHER_INDEX)).isTrue();
    }

    @Test
    void 다른_제약이거나_UNIQUE_위반이_아니면_거짓이다() {
        assertThat(UniqueIndexViolation.isOf(violation(UNIQUE_VIOLATION, "pk_lecture_review"), INDEX)).isFalse();
        assertThat(UniqueIndexViolation.isOf(violation(CHECK_VIOLATION, INDEX), INDEX)).isFalse();
        assertThat(UniqueIndexViolation.isOf(violation(UNIQUE_VIOLATION, null), INDEX)).isFalse();
    }

    @Test
    void Hibernate가_메시지에서_뽑은_제약_이름만_있으면_거짓이다() {
        assertThat(UniqueIndexViolation.isOf(hibernateConstraintNameOnly(INDEX), INDEX)).isFalse();
    }
}
