package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.chatbot.app.FaqSeed;
import com.hapbang.auxiliary.chatbot.app.FaqSeedReader;
import com.hapbang.auxiliary.chatbot.app.FaqSyncService;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * FAQ 챗봇 API(#715)를 실제 보안 설정·공통 오류 처리·PostgreSQL·Elasticsearch와 가짜 모델로 확인한다.
 * 로그인은 운영처럼 Gateway 사용자 헤더로 한다. 호출 제한은 이 클래스만 분당 3회로 줄인다(회원 ID를 테스트마다 다르게 쓴다).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
@TestPropertySource(properties = "hapbang.chatbot.faq.rate-limit.limit=3")
class ChatbotFaqApiIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FaqSyncService syncService;

    @Autowired
    private FaqSeedReader seedReader;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private MeterRegistry meterRegistry;

    private FaqSeed cancelFaq;

    @BeforeEach
    void setUp() throws Exception {
        embeddingModel.reset();
        chatModel.reset();
        deleteIndex();
        List<FaqSeed> seeds = seedReader.read();
        cancelFaq = seeds.stream().filter(seed -> seed.code().equals("FAQ-501")).findFirst().orElseThrow();
        embeddingModel.put(cancelFaq.question(), FakeEmbeddingModel.axis(0));
        embeddingModel.put(FakeEmbeddingModel.query("출연 취소하면?"), FakeEmbeddingModel.axis(0));
        syncService.syncAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("로그인하지 않으면 모든 챗봇 API가 401이다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/chatbot/faqs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/chatbot/faqs/search").param("q", "출연 취소하면?")).andExpect(status().isUnauthorized());
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?")).andExpect(status().isUnauthorized());
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq/stream", "출연 취소하면?")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 유저(USER)도 쓴다. FAQ 모아보기는 전체 53건, 분류를 주면 그 분류만 시드 순서대로 준다")
    void listFaqs() throws Exception {
        mockMvc.perform(get("/api/v1/chatbot/faqs").with(login(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(53)))
                .andExpect(jsonPath("$.data[0].code").value("FAQ-001"))
                .andExpect(jsonPath("$.data[0].category").value("ACCOUNT"));
        mockMvc.perform(get("/api/v1/chatbot/faqs").param("category", "HP").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)))
                .andExpect(jsonPath("$.data[0].code").value("FAQ-201"));
    }

    @Test
    @DisplayName("FAQ 검색은 LLM 없이 벡터 검색 결과(code·question·answer·score)를 준다")
    void searchFaqs() throws Exception {
        mockMvc.perform(get("/api/v1/chatbot/faqs/search").param("q", "출연 취소하면?").with(login(2L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("FAQ-501"))
                .andExpect(jsonPath("$.data[0].answer").value(cancelFaq.answer()))
                .andExpect(jsonPath("$.data[0].score").isNumber());
        assertThat(chatModel.calls()).isZero();
    }

    @Test
    @DisplayName("FAQ 대화(한 번에): 답변·근거 FAQ·fallback=false")
    void chat() throws Exception {
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(3L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(FakeChatModel.ANSWER_PREFIX + " " + cancelFaq.answer()))
                .andExpect(jsonPath("$.data.sources[0].code").value("FAQ-501"))
                .andExpect(jsonPath("$.data.sources[0].question").value(cancelFaq.question()))
                .andExpect(jsonPath("$.data.fallback").value(false));
    }

    @Test
    @DisplayName("모델이 실패하면 200으로 검색 상위 1건의 답변을 주고 fallback=true다")
    void chatFallback() throws Exception {
        chatModel.mode(FakeChatModel.Mode.FAIL);

        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(4L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(cancelFaq.answer()))
                .andExpect(jsonPath("$.data.fallback").value(true));
    }

    @Test
    @DisplayName("SSE: event:token은 {\"text\"} JSON(앞 공백 보존), 마지막은 event:done {sources, fallback}")
    void streamFormat() throws Exception {
        MvcResult started = mockMvc.perform(chat("/api/v1/chatbot/chat/faq/stream", "출연 취소하면?")
                        .with(login(5L, Role.USER)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/event-stream")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).startsWith("event:token\ndata:{\"text\":\"" + FakeChatModel.ANSWER_PREFIX + "\"}\n\n");
        assertThat(body).contains("data:{\"text\":\" ");
        assertThat(body).endsWith("event:done\ndata:{\"sources\":[{\"code\":\"FAQ-501\",\"question\":\""
                + cancelFaq.question() + "\"}],\"fallback\":false}\n\n");
    }

    @Test
    @DisplayName("SSE: 첫 조각 전 실패는 fallback 답 token 하나와 done(fallback=true)")
    void streamFallback() throws Exception {
        chatModel.mode(FakeChatModel.Mode.FAIL);
        MvcResult started = mockMvc.perform(chat("/api/v1/chatbot/chat/faq/stream", "출연 취소하면?")
                        .with(login(6L, Role.USER)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:token\ndata:{\"text\":\"" + cancelFaq.answer() + "\"}")
                .contains("event:done\ndata:{\"sources\":[{\"code\":\"FAQ-501\"")
                .contains("\"fallback\":true}");
    }

    @Test
    @DisplayName("검색·대화를 합쳐 회원마다 분당 한도를 넘으면 429·Retry-After(ProblemDetail). 스트림도 열지 않고 429다. 다른 회원은 따로 센다")
    void rateLimit() throws Exception {
        long userId = 7L;
        mockMvc.perform(get("/api/v1/chatbot/faqs/search").param("q", "출연 취소하면?").with(login(userId, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(userId, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(userId, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(userId, Role.USER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq/stream", "출연 취소하면?").with(login(userId, Role.USER))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // FAQ 모아보기는 세지 않는다
        mockMvc.perform(get("/api/v1/chatbot/faqs").with(login(userId, Role.USER))).andExpect(status().isOk());
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "출연 취소하면?").with(login(userId + 1000, Role.USER)))
                .andExpect(status().isOk());
        assertThat(meterRegistry.get("hapbang.chatbot.rate.limit.rejected").tag("feature", "faq_chat").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("요청 값 검증: 메시지 1~500자, 검색어 1~200자, 모르는 분류는 400")
    void validation() throws Exception {
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", " ").with(login(8L, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(chat("/api/v1/chatbot/chat/faq", "가".repeat(501)).with(login(8L, Role.USER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/chatbot/faqs/search").param("q", "가".repeat(201)).with(login(8L, Role.USER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/chatbot/faqs/search").param("q", "").with(login(8L, Role.USER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/chatbot/faqs").param("category", "COUPON").with(login(8L, Role.USER)))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder chat(String path, String message) {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor login(long userId, Role role) {
        return request -> {
            request.addHeader(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
            request.addHeader(GatewayUserHeaderFilter.USER_ROLE, role.name());
            return request;
        };
    }

    private void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-faq-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
