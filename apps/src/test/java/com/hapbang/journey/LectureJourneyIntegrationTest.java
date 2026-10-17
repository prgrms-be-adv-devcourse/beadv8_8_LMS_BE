package com.hapbang.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.journey.support.JourneyFixtures;
import com.hapbang.journey.support.JourneyTest;
import com.hapbang.lecture.app.OrderPayoutUseCase;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 시나리오 2 — 강의 (판매자 → 구매자 → 지급) (#326). 검증된 인플루언서가 실제 파일 업로드(S3Mock)·등록·검수 승인을 거쳐
 * 공개한 강의를, 가입한 일반 회원이 충전 HP로 사서(실제 Billing 보류) 확정하고, 지급 배치로 판매자 수익 HP가 되는지 본다.
 * 스케줄러는 꺼져 있어 지급 배치는 UseCase를 직접 부르고, 지급 예정 시각(구매일 + 31일)만 SQL로 과거로 옮긴다.
 */
@JourneyTest
@Import(S3MockTestcontainersConfig.class)
class LectureJourneyIntegrationTest {

    private static final long FUNDED_HP = 50_000;
    private static final long PRICE_HP = 10_000;
    private static final Long ADMIN_ID = 900_001L;
    private static final byte[] THUMBNAIL = "journey-thumbnail".getBytes();
    private static final byte[] VIDEO = "journey-video-bytes".getBytes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JourneyFixtures fixtures;

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long buyerId;

    @BeforeEach
    void setUp() {
        sellerId = fixtures.member("journey-seller", "여정판매자", Role.VERIFIED_INFLUENCER);
        buyerId = fixtures.user("journey-buyer", "여정구매자");
        fixtures.fund(buyerId, FUNDED_HP, "lecture-buyer");
    }

    @Test
    @DisplayName("업로드·등록·검수 승인 → 공개 → 장바구니·주문(보류) → 동의 전 접근 거절 → 구매 확정 → 접근 허용 → 지급 배치로 판매자 수익 HP")
    void saleJourney() throws Exception {
        Long lectureId = registerAndApproveLecture();
        Long contentId = longOf(mockMvc.perform(get("/api/v1/lectures/{id}", lectureId)
                        .with(TestLogin.as(buyerId, Role.USER)))
                .andExpect(status().isOk()), "$.data.contents[0].contentId");

        // 장바구니에 담고 주문하면 강의 가격만큼 충전 HP가 보류되고 장바구니에서 빠진다
        mockMvc.perform(post("/api/v1/lectures/cart/items").with(TestLogin.as(buyerId, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lectureId": %d}""".formatted(lectureId)))
                .andExpect(status().isNoContent());
        ResultActions ordered = order(lectureId);
        Long orderId = longOf(ordered, "$.data.orderId");
        Long orderItemId = longOf(ordered, "$.data.items[0].orderItemId");

        assertThat(fixtures.heldHp(BillingReferenceType.LECTURE_ORDER_ITEM, orderItemId)).isEqualTo(PRICE_HP);
        Wallet buyerWallet = fixtures.wallet(buyerId);
        assertThat(buyerWallet.getChargeBalanceHp()).isEqualTo(FUNDED_HP - PRICE_HP);
        assertThat(buyerWallet.getHeldChargeHp()).isEqualTo(PRICE_HP);
        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(buyerId, Role.USER)))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 재생 동의(또는 확정) 전에는 콘텐츠를 볼 수 없고, 구매 확정 뒤에는 볼 수 있다
        String accessPath = "/api/v1/lectures/" + lectureId + "/contents/" + contentId + "/access";
        mockMvc.perform(get(accessPath).with(TestLogin.as(buyerId, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAY_CONSENT_REQUIRED"));
        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{itemId}/confirm", orderId, orderItemId)
                        .with(TestLogin.as(buyerId, Role.USER)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmationType").value("DIRECT"));
        mockMvc.perform(get(accessPath).with(TestLogin.as(buyerId, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty());

        // 지급 예정 시각(구매일 + 31일)이 지나면 지급 배치가 보류 HP를 판매자 수익 HP로 옮긴다
        jdbcTemplate.update("UPDATE lectures.order_item SET payout_scheduled_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), orderItemId);
        orderPayoutUseCase.payOutDueItems();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM lectures.order_item WHERE id = ?", String.class,
                orderItemId)).isEqualTo("PAID_OUT");
        assertThat(fixtures.wallet(sellerId).getRevenueBalanceHp()).isEqualTo(PRICE_HP);
        assertThat(fixtures.wallet(buyerId).getHeldChargeHp()).isZero();
        assertThat(fixtures.heldHp(BillingReferenceType.LECTURE_ORDER_ITEM, orderItemId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT receiver_user_id || ' ' || paid_amount_hp || ' ' || status FROM billing.settlement
                WHERE source_type = 'LECTURE_ORDER_ITEM' AND source_id = ?""", String.class, orderItemId))
                .isEqualTo(sellerId + " " + PRICE_HP + " COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_ledger
                WHERE operation_key = (SELECT idempotency_key FROM billing.settlement WHERE source_id = ?)""",
                Long.class, orderItemId)).isEqualTo(2);
    }

    @Test
    @DisplayName("구매 확정 전 구매 취소 → 보류 반환, 지갑 원복")
    void cancelJourney() throws Exception {
        Long lectureId = registerAndApproveLecture();
        ResultActions ordered = order(lectureId);
        Long orderId = longOf(ordered, "$.data.orderId");
        Long orderItemId = longOf(ordered, "$.data.items[0].orderItemId");

        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{itemId}/cancel", orderId, orderItemId)
                        .with(TestLogin.as(buyerId, Role.USER)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        Wallet buyerWallet = fixtures.wallet(buyerId);
        assertThat(buyerWallet.getChargeBalanceHp()).isEqualTo(FUNDED_HP);
        assertThat(buyerWallet.getHeldChargeHp()).isZero();
        assertThat(fixtures.heldHp(BillingReferenceType.LECTURE_ORDER_ITEM, orderItemId)).isZero();
    }

    /** 판매자가 썸네일·영상을 올려 강의를 등록하고 관리자가 검수 승인 → 공개 목록에 나온다 */
    private Long registerAndApproveLecture() throws Exception {
        Long thumbnailId = upload("LECTURE_THUMBNAIL", "thumbnail.png", "image/png", THUMBNAIL);
        Long videoId = upload("LECTURE_VIDEO", "lesson1.mp4", "video/mp4", VIDEO);
        Long lectureId = longOf(mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title": "방송 장비 세팅 강의", "description": "처음 방송하는 사람을 위한 장비 세팅",
                                 "priceHp": %d, "thumbnailFileId": %d,
                                 "contents": [{"fileId": %d, "title": "1강 장비 고르기", "contentType": "VIDEO"}]}"""
                                .formatted(PRICE_HP, thumbnailId, videoId)))
                .andExpect(status().isCreated()), "$.data.id");

        // 검수 전에는 공개 목록에 없다
        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(jsonPath("$.data.content[*].lectureId", not(hasItem(lectureId.intValue()))));
        mockMvc.perform(post("/api/v1/lectures/admin/{id}/inspection/approve", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("여정판매자"))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").isNotEmpty());
        return lectureId;
    }

    private Long upload(String fileType, String filename, String contentType, byte[] bytes) throws Exception {
        Long fileId = longOf(mockMvc.perform(post("/api/v1/lectures/files")
                        .with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fileType": "%s", "originalFilename": "%s", "contentType": "%s", "fileSize": %d}"""
                                .formatted(fileType, filename, contentType, bytes.length)))
                .andExpect(status().isCreated()), "$.data.fileId");
        mockMvc.perform(put("/api/v1/lectures/files/{id}/content", fileId)
                        .with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(contentType).content(bytes))
                .andExpect(status().isNoContent());
        return fileId;
    }

    private ResultActions order(Long lectureId) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/orders").with(TestLogin.as(buyerId, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lectureIds": [%d]}""".formatted(lectureId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.items[0].status").value("PURCHASED"));
    }

    private static long longOf(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }
}
