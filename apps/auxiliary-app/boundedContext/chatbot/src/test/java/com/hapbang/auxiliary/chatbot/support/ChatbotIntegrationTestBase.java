package com.hapbang.auxiliary.chatbot.support;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.hapbang.auxiliary.chatbot.app.ChatbotConfig;
import com.hapbang.auxiliary.chatbot.out.AppearanceSyncSource;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 챗봇 통합 테스트 기반. 시계와 출연 원본을 가짜로 바꾸고(모든 하위 클래스가 같은 컨텍스트를 쓴다), 테스트마다 되돌리고 사본을 비운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
public abstract class ChatbotIntegrationTestBase {

    @TestBean(name = ChatbotConfig.CLOCK, methodName = ChatbotTestClock.FACTORY)
    protected Clock clockBean;

    @TestBean(methodName = FakeAppearanceSyncSource.FACTORY)
    protected AppearanceSyncSource sourceBean;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected final ChatbotTestClock clock = ChatbotTestClock.clock();
    protected final FakeAppearanceSyncSource source = FakeAppearanceSyncSource.source();
    protected CopyRows rows;

    @BeforeEach
    void resetChatbot() {
        clock.reset();
        source.reset();
        rows = new CopyRows(jdbcTemplate);
        rows.clear();
    }
}
