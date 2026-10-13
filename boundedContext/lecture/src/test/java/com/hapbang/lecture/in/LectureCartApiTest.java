package com.hapbang.lecture.in;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 장바구니 API를 HTTP부터 DB까지 관통해 검증한다. 담기·빼기 세부 규칙은 CartTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureCartApiTest {

    private static final Long SELLER_ID = 1L;
    private static final Long BUYER_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 판매_중인_강의를_담으면_최근_담은_순으로_조회되고_합계에_더해진다() throws Exception {
        Long firstLectureId = registerOnSale("OBS 방송 세팅", 10_000);
        Long secondLectureId = registerOnSale("마이크 고르기", 5_000);

        addItem(BUYER_ID, Role.USER, firstLectureId).andExpect(status().isNoContent());
        addItem(BUYER_ID, Role.USER, secondLectureId).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].lectureId").value(secondLectureId))
                .andExpect(jsonPath("$.items[0].title").value("마이크 고르기"))
                .andExpect(jsonPath("$.items[0].priceHp").value(5000))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").isEmpty())
                .andExpect(jsonPath("$.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.items[1].lectureId").value(firstLectureId))
                .andExpect(jsonPath("$.purchasableTotalHp").value(15000));
    }

    @Test
    void 장바구니가_없으면_빈_목록과_합계_0을_돌려준다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.purchasableTotalHp").value(0));
    }

    @Test
    void 담은_뒤_삭제된_강의는_구매_불가로_표시되고_합계에서_빠진다() throws Exception {
        Long deletedLectureId = registerOnSale("OBS 방송 세팅", 10_000);
        Long lectureId = registerOnSale("마이크 고르기", 5_000);
        addItem(BUYER_ID, Role.USER, deletedLectureId).andExpect(status().isNoContent());
        addItem(BUYER_ID, Role.USER, lectureId).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", deletedLectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].lectureId").value(deletedLectureId))
                .andExpect(jsonPath("$.items[1].title").value("OBS 방송 세팅"))
                .andExpect(jsonPath("$.items[1].purchasable").value(false))
                .andExpect(jsonPath("$.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.purchasableTotalHp").value(5000));
    }

    @Test
    void 없는_강의와_판매_중이_아닌_강의는_담을_수_없다() throws Exception {
        Long pendingLectureId = register("OBS 방송 세팅", 10_000);

        addItem(BUYER_ID, Role.USER, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
        addItem(BUYER_ID, Role.USER, pendingLectureId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 본인_강의는_담을_수_없다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        addItem(SELLER_ID, Role.VERIFIED_INFLUENCER, lectureId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWN_LECTURE_NOT_PURCHASABLE"));
    }

    @Test
    void 이미_담은_강의는_다시_담을_수_없다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);
        addItem(BUYER_ID, Role.USER, lectureId).andExpect(status().isNoContent());

        addItem(BUYER_ID, Role.USER, lectureId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_CART"));
    }

    @Test
    void 강의를_빼면_장바구니에서_사라진다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);
        addItem(BUYER_ID, Role.USER, lectureId).andExpect(status().isNoContent());

        removeItem(BUYER_ID, Role.USER, lectureId).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.purchasableTotalHp").value(0));
    }

    @Test
    void 담지_않은_강의를_빼도_204를_돌려준다() throws Exception {
        removeItem(BUYER_ID, Role.USER, 999_999L).andExpect(status().isNoContent());

        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);
        addItem(BUYER_ID, Role.USER, lectureId).andExpect(status().isNoContent());
        removeItem(BUYER_ID, Role.USER, 999_999L).andExpect(status().isNoContent());
    }

    @Test
    void 강의_ID가_없으면_담을_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures/cart/items")
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("lectureId"));
    }

    @Test
    void 유저_인플루언서_인증된_인플루언서는_장바구니를_쓸_수_있다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);
        long userId = 10L;

        for (Role role : List.of(Role.USER, Role.INFLUENCER, Role.VERIFIED_INFLUENCER)) {
            userId++;
            addItem(userId, role, lectureId).andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(userId, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].lectureId").value(lectureId));
            removeItem(userId, role, lectureId).andExpect(status().isNoContent());
        }
    }

    @Test
    void 관리자는_장바구니를_조회_담기_빼기할_수_없다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        mockMvc.perform(get("/api/v1/lectures/cart").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isForbidden());
        addItem(ADMIN_ID, Role.ADMIN, lectureId).andExpect(status().isForbidden());
        removeItem(ADMIN_ID, Role.ADMIN, lectureId).andExpect(status().isForbidden());
    }

    @Test
    void 비회원은_장바구니를_쓸_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_강의를_동시에_담으면_하나만_담기고_나머지는_409다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        List<Integer> statuses = concurrently(
                () -> addItem(BUYER_ID, Role.USER, lectureId).andReturn().getResponse().getStatus(),
                () -> addItem(BUYER_ID, Role.USER, lectureId).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(count("select count(*) from lectures.cart_item where deleted_at is null")).isEqualTo(1);
    }

    @Test
    void 처음_담기를_동시에_해도_장바구니는_하나다() throws Exception {
        Long firstLectureId = registerOnSale("OBS 방송 세팅", 10_000);
        Long secondLectureId = registerOnSale("마이크 고르기", 5_000);

        List<Integer> statuses = concurrently(
                () -> addItem(BUYER_ID, Role.USER, firstLectureId).andReturn().getResponse().getStatus(),
                () -> addItem(BUYER_ID, Role.USER, secondLectureId).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(204, 204);
        assertThat(count("select count(*) from lectures.cart where user_id = " + BUYER_ID)).isEqualTo(1);
        assertThat(count("select count(*) from lectures.cart_item where deleted_at is null")).isEqualTo(2);
    }

    /** 두 요청을 같은 시점에 출발시키고 응답 상태를 요청 순서대로 돌려준다. */
    private List<Integer> concurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> request : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private ResultActions addItem(Long userId, Role role, Long lectureId) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/cart/items")
                .with(TestLogin.as(userId, role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lectureId\": %d}".formatted(lectureId)));
    }

    private ResultActions removeItem(Long userId, Role role, Long lectureId) throws Exception {
        return mockMvc.perform(delete("/api/v1/lectures/cart/items/{lectureId}", lectureId)
                .with(TestLogin.as(userId, role)).with(csrf()));
    }

    private Long registerOnSale(String title, long priceHp) throws Exception {
        Long lectureId = register(title, priceHp);
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/approve", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
        return lectureId;
    }

    private Long register(String title, long priceHp) throws Exception {
        String body = """
                {
                  "title": "%s",
                  "priceHp": %d,
                  "contents": [{"fileId": 101, "title": "1강", "contentType": "VIDEO"}]
                }
                """.formatted(title, priceHp);
        String response = mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
