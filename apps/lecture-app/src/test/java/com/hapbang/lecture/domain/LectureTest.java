package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class LectureTest {

    @Test
    void 등록하면_검수_대기로_시작한다() {
        Lecture lecture = pendingLecture();

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.PENDING_INSPECTION);
        assertThat(lecture.getSellerUserId()).isEqualTo(SELLER_ID);
        assertThat(lecture.getInspectionRequestedAt()).isEqualTo(NOW);
        assertThat(lecture.isOnSale()).isFalse();
    }

    @Test
    void 콘텐츠는_목록_순서대로_1부터_순서를_매긴다() {
        Lecture lecture = pendingLecture();

        assertThat(lecture.getContents())
                .extracting(LectureContent::getSequence, LectureContent::getFileId, LectureContent::getContentType)
                .containsExactly(
                        tuple(1, 101L, ContentType.VIDEO),
                        tuple(2, 102L, ContentType.DOCUMENT));
    }

    @Test
    void 콘텐츠_없이_등록할_수_없다() {
        assertThatThrownBy(() -> Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, THUMBNAIL_FILE_ID,
                List.of(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 999, 1_500})
    void 가격은_1000_이상_1000_단위여야_한다(long priceHp) {
        assertThatThrownBy(() -> Lecture.register(SELLER_ID, TITLE, DESCRIPTION, priceHp, THUMBNAIL_FILE_ID,
                contentSpecs(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_PRICE);
    }

    @Test
    void 승인하면_판매_중이_된다() {
        Lecture lecture = pendingLecture();

        lecture.approve(ADMIN_ID, NOW.plusHours(1));

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.ON_SALE);
        assertThat(lecture.isOnSale()).isTrue();
        assertThat(lecture.getInspectedBy()).isEqualTo(ADMIN_ID);
        assertThat(lecture.getInspectedAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void 반려하면_사유를_남긴다() {
        Lecture lecture = pendingLecture();

        lecture.reject(ADMIN_ID, "영상 음질이 너무 낮습니다.", NOW);

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.REJECTED);
        assertThat(lecture.getRejectionReason()).isEqualTo("영상 음질이 너무 낮습니다.");
    }

    @Test
    void 반려_사유는_필수다() {
        Lecture lecture = pendingLecture();

        assertThatThrownBy(() -> lecture.reject(ADMIN_ID, " ", NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.REJECTION_REASON_REQUIRED);
    }

    @Test
    void 검수_대기가_아니면_승인하거나_반려할_수_없다() {
        Lecture lecture = onSaleLecture();

        assertThatThrownBy(() -> lecture.approve(ADMIN_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_PENDING_INSPECTION);
        assertThatThrownBy(() -> lecture.reject(ADMIN_ID, "사유", NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_PENDING_INSPECTION);
    }

    @Test
    void 반려된_강의는_재검수를_신청할_수_있고_이전_반려_사유는_남는다() {
        Lecture lecture = pendingLecture();
        lecture.reject(ADMIN_ID, "사유", NOW);

        lecture.requestInspection(SELLER_ID, NOW.plusDays(1));

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.PENDING_INSPECTION);
        assertThat(lecture.getInspectionRequestedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(lecture.getRejectionReason()).isEqualTo("사유");
    }

    @Test
    void 반려되지_않은_강의는_재검수를_신청할_수_없다() {
        assertThatThrownBy(() -> pendingLecture().requestInspection(SELLER_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_REJECTED);
        assertThatThrownBy(() -> onSaleLecture().requestInspection(SELLER_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_REJECTED);
    }

    @Test
    void 반려된_강의는_콘텐츠를_바꿀_수_있고_반려_상태를_유지한다() {
        Lecture lecture = rejectedLecture();
        List<LectureContent> before = lecture.getContents();

        lecture.replaceContents(SELLER_ID,
                List.of(new LectureContentSpec(201L, "1강 다시 찍음", ContentType.VIDEO)), NOW.plusDays(1));

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.REJECTED);
        assertThat(lecture.getContents())
                .extracting(LectureContent::getFileId, LectureContent::getSequence)
                .containsExactly(tuple(201L, 1));
        assertThat(before).allSatisfy(content -> assertThat(content.getDeletedAt()).isEqualTo(NOW.plusDays(1)));
    }

    @Test
    void 검수_대기_중이거나_판매_중인_강의는_콘텐츠를_바꿀_수_없다() {
        Lecture pending = pendingLecture();
        Lecture onSale = onSaleLecture();

        assertThatThrownBy(() -> pending.replaceContents(SELLER_ID, contentSpecs(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_NOT_EDITABLE);
        assertThatThrownBy(() -> onSale.replaceContents(SELLER_ID, contentSpecs(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_NOT_EDITABLE);
        assertThat(onSale.getStatus()).isEqualTo(LectureStatus.ON_SALE);
    }

    @Test
    void 잘못된_콘텐츠로_바꾸면_기존_콘텐츠를_그대로_둔다() {
        Lecture lecture = rejectedLecture();
        List<LectureContentSpec> invalid = List.of(
                new LectureContentSpec(201L, "정상", ContentType.VIDEO),
                new LectureContentSpec(null, "파일 없음", ContentType.VIDEO));

        assertThatThrownBy(() -> lecture.replaceContents(SELLER_ID, invalid, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_REQUEST);
        assertThat(lecture.getContents()).hasSize(2)
                .allSatisfy(content -> assertThat(content.getDeletedAt()).isNull());
    }

    @Test
    void 콘텐츠를_모두_뺄_수_없다() {
        Lecture lecture = rejectedLecture();

        assertThatThrownBy(() -> lecture.replaceContents(SELLER_ID, List.of(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_REQUIRED);
    }

    @Test
    void 판매_중에_메타_정보를_바꿔도_판매_중이다() {
        Lecture lecture = onSaleLecture();

        lecture.updateInfo(SELLER_ID, "새 제목", "새 소개", 20_000L, 300L, NOW.plusDays(1));

        assertThat(lecture.getStatus()).isEqualTo(LectureStatus.ON_SALE);
        assertThat(lecture.getTitle()).isEqualTo("새 제목");
        assertThat(lecture.getPriceHp()).isEqualTo(20_000L);
        assertThat(lecture.getThumbnailFileId()).isEqualTo(300L);
    }

    @Test
    void 판매자가_아니면_수정_삭제_재검수_신청을_할_수_없다() {
        Lecture lecture = pendingLecture();

        assertThatThrownBy(() -> lecture.updateInfo(OTHER_USER_ID, TITLE, DESCRIPTION, PRICE_HP, null, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_LECTURE_SELLER);
        assertThatThrownBy(() -> lecture.replaceContents(OTHER_USER_ID, contentSpecs(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_LECTURE_SELLER);
        assertThatThrownBy(() -> lecture.delete(OTHER_USER_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_LECTURE_SELLER);
        assertThatThrownBy(() -> lecture.requestInspection(OTHER_USER_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_LECTURE_SELLER);
    }

    @Test
    void 삭제하면_판매하지_않고_다시_삭제해도_시각은_그대로다() {
        Lecture lecture = onSaleLecture();

        lecture.delete(SELLER_ID, NOW);
        lecture.delete(SELLER_ID, NOW.plusDays(1));

        assertThat(lecture.isDeleted()).isTrue();
        assertThat(lecture.isOnSale()).isFalse();
        assertThat(lecture.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void 삭제된_강의는_수정하거나_검수할_수_없다() {
        Lecture lecture = pendingLecture();
        lecture.delete(SELLER_ID, NOW);

        assertThatThrownBy(() -> lecture.updateInfo(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_NOT_FOUND);
        assertThatThrownBy(() -> lecture.replaceContents(SELLER_ID, contentSpecs(), NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_NOT_FOUND);
        assertThatThrownBy(() -> lecture.approve(ADMIN_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_NOT_FOUND);
    }

    @Test
    void 판매자는_검수_대기_강의의_콘텐츠를_볼_수_있다() {
        Lecture lecture = pendingLecture();

        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(SELLER_ID, false, null));
    }

    @Test
    void 판매자_관리자가_아니면_판매_중_강의는_CONTENT_ACCESS_DENIED() {
        Lecture lecture = onSaleLecture();

        assertThatThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, null))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_ACCESS_DENIED);
    }

    @Test
    void 관리자는_판매자가_아니어도_콘텐츠를_볼_수_있다() {
        Lecture lecture = pendingLecture();

        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(ADMIN_ID, true, null));
    }

    @Test
    void 판매자와_관리자는_삭제된_강의의_콘텐츠도_볼_수_있다() {
        Lecture lecture = onSaleLecture();
        lecture.delete(SELLER_ID, NOW);

        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(SELLER_ID, false, null));
        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(ADMIN_ID, true, null));
    }

    @Test
    void 판매자_관리자가_아니면_판매_중이_아닌_강의는_LECTURE_NOT_FOUND() {
        Lecture deleted = onSaleLecture();
        deleted.delete(SELLER_ID, NOW);

        for (Lecture lecture : List.of(pendingLecture(), rejectedLecture(), deleted)) {
            assertThatThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, null))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_NOT_FOUND);
        }
    }

    @Test
    void 재생_동의_전_구매자는_PLAY_CONSENT_REQUIRED와_주문_ID를_받는다() {
        Lecture lecture = withId(onSaleLecture(), 10L);
        OrderItem purchased = heldItem(lecture);

        assertThatThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, purchased))
                .isInstanceOfSatisfying(PlayConsentRequiredException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(LectureErrorCode.PLAY_CONSENT_REQUIRED);
                    assertThat(e.getProperties()).containsExactly(entry("orderId", 100L), entry("orderItemId", 1000L));
                });
    }

    @Test
    void 구매_확정한_구매자는_콘텐츠를_볼_수_있다() {
        Lecture lecture = withId(onSaleLecture(), 10L);
        OrderItem confirmed = heldItem(lecture);
        confirmed.getOrder().confirmItem(confirmed.getId(), NOW);
        OrderItem paidOut = heldItem(lecture);
        paidOut.getOrder().confirmItem(paidOut.getId(), NOW);
        // 지급 메서드가 아직 없어(#149) 상태만 넣는다
        ReflectionTestUtils.setField(paidOut, "status", OrderItemStatus.PAID_OUT);

        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, confirmed));
        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, paidOut));
    }

    @Test
    void 구매_확정한_구매자는_삭제된_강의의_콘텐츠도_볼_수_있다() {
        Lecture lecture = withId(onSaleLecture(), 10L);
        OrderItem confirmed = heldItem(lecture);
        confirmed.getOrder().consentToPlay(confirmed.getId(), NOW);
        lecture.delete(SELLER_ID, NOW);

        assertThatNoException().isThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, confirmed));
    }

    @Test
    void 결제_처리_중인_구매자는_콘텐츠를_볼_수_없다() {
        Lecture lecture = onSaleLecture();
        OrderItem pending = LectureOrder.place(OTHER_USER_ID, List.of(withId(lecture, 10L)), Set.of(), NOW).getItems()
                .getFirst();

        assertThatThrownBy(() -> lecture.validateContentViewer(OTHER_USER_ID, false, pending))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CONTENT_ACCESS_DENIED);
    }

    /** {@code OTHER_USER_ID}가 산 항목(주문 ID 100, 항목 ID 1000) */
    private static OrderItem heldItem(Lecture lecture) {
        LectureOrder order = LectureOrder.place(OTHER_USER_ID, List.of(lecture), Set.of(), NOW);
        order.markPaid(NOW);
        ReflectionTestUtils.setField(order, "id", 100L);
        OrderItem item = order.getItems().getFirst();
        ReflectionTestUtils.setField(item, "id", 1000L);
        return item;
    }
}
