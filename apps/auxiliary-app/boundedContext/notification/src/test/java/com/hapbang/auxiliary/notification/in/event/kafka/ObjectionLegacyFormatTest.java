package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

/**
 * #818 이전 형식 판별(ND-086, NT-KAF-08). 값이 비었다는 사실만으로 과거 이벤트라고 보지 않고, 원본 JSON에 #818이 더한 필드의 키가
 * 모두 없을 때만 이전 형식으로 본다.
 */
class ObjectionLegacyFormatTest {

    @Test
    void 추가_필드_키가_모두_없으면_이전_형식이다() {
        String json = """
                {"commandId":"c1","inquiryId":55,"orderItemId":900,"accepted":true}""";

        assertThat(ObjectionLegacyFormat.classify(LectureObjectionResolvedEvent.class, bytes(json)))
                .isEqualTo(ObjectionLegacyFormat.LEGACY);
    }

    @Test
    void 추가_필드_키가_있는데_값이_null이면_판별할_수_없다() {
        String json = """
                {"inquiryId":55,"orderItemId":900,"buyerUserId":null,"sellerUserId":null,"lectureTitle":null,\
                "returnedAt":null}""";

        assertThat(ObjectionLegacyFormat.classify(LectureObjectionReturnCompletedEvent.class, bytes(json)))
                .isEqualTo(ObjectionLegacyFormat.UNDETERMINED);
    }

    @Test
    void 추가_필드_키가_일부만_없으면_판별할_수_없다() {
        String json = """
                {"commandId":"c2","inquiryId":55,"orderItemId":900,"buyerUserId":10,"sellerUserId":20}""";

        assertThat(ObjectionLegacyFormat.classify(LectureObjectionWithdrawnEvent.class, bytes(json)))
                .isEqualTo(ObjectionLegacyFormat.UNDETERMINED);
    }

    @Test
    void 추가_필드가_모두_값과_함께_있으면_지금_형식이다() {
        String json = """
                {"commandId":"c1","inquiryId":55,"orderItemId":900,"accepted":false,"buyerUserId":10,\
                "sellerUserId":20,"lectureTitle":"자바 입문","resolvedAt":"2026-11-11T10:00:00+09:00"}""";

        assertThat(ObjectionLegacyFormat.classify(LectureObjectionResolvedEvent.class, bytes(json)))
                .isEqualTo(ObjectionLegacyFormat.CURRENT);
    }

    @Test
    void 반환_지연은_회차_키까지_본다() {
        String legacy = """
                {"inquiryId":55,"orderItemId":900}""";
        String withoutSequence = """
                {"inquiryId":55,"orderItemId":900,"buyerUserId":10,"sellerUserId":20,"lectureTitle":"자바 입문",\
                "occurredAt":"2026-11-11T10:00:00+09:00"}""";

        assertThat(ObjectionLegacyFormat.classify(LectureObjectionReturnActionRequiredEvent.class, bytes(legacy)))
                .isEqualTo(ObjectionLegacyFormat.LEGACY);
        assertThat(ObjectionLegacyFormat.classify(LectureObjectionReturnActionRequiredEvent.class,
                bytes(withoutSequence)))
                .isEqualTo(ObjectionLegacyFormat.UNDETERMINED);
    }

    @Test
    void JSON_객체가_아닌_본문은_이전_형식이_아니라_판별할_수_없다() {
        for (String body : new String[] {"", "null", "[]", "\"text\"", "1"}) {
            assertThat(ObjectionLegacyFormat.classify(LectureObjectionResolvedEvent.class, bytes(body)))
                    .as("본문 %s", body)
                    .isEqualTo(ObjectionLegacyFormat.UNDETERMINED);
        }
    }

    @Test
    void 이의_제기_결과가_아닌_이벤트는_판별하지_않는다() {
        assertThatThrownBy(() -> ObjectionLegacyFormat.classify(AppearanceCancelRequestedEvent.class,
                bytes("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
