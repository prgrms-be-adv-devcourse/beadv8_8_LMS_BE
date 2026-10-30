package com.hapbang.chat.in.support;

import org.springframework.test.context.TestContext;

import com.hapbang.testsupport.DatabaseCleanUpListener;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 E2E의 DB 정리. {@link ChatPipelineIsolationListener}가 전달 파이프라인의 작업이 아직 살아 있다고 표시했으면 DB를 비우지 않는다.
 * 살아 있는 작업이 정리 중인 DB를 건드리지 않게 하고, 그 테스트는 실패로 남긴다(컨텍스트는 닫힌다).
 */
@Slf4j
public class ChatE2EDatabaseCleanUpListener extends DatabaseCleanUpListener {

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (Boolean.TRUE.equals(testContext.getAttribute(ChatPipelineIsolationListener.PIPELINE_STILL_RUNNING))) {
            log.error("전달 파이프라인 작업이 끝나지 않아 DB 정리를 건너뜀 test={}", testContext.getTestMethod().getName());
            return;
        }
        super.afterTestMethod(testContext);
    }
}
