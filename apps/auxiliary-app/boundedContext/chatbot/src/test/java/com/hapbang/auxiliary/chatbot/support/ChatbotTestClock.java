package com.hapbang.auxiliary.chatbot.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;

/**
 * 테스트가 움직이는 챗봇 시계. 테스트 클래스에서 {@code @TestBean(name = ChatbotConfig.CLOCK, methodName = ChatbotTestClock.FACTORY)}로
 * 바꿔 끼운다. 인스턴스가 하나라 테스트마다 {@link #reset()}한다.
 */
public final class ChatbotTestClock extends Clock {

    public static final String FACTORY = "com.hapbang.auxiliary.chatbot.support.ChatbotTestClock#clock";
    /** 기본 시각: 월요일 09:00 KST */
    public static final LocalDateTime START = LocalDateTime.of(2026, 11, 9, 9, 0);

    private static final ChatbotTestClock INSTANCE = new ChatbotTestClock();

    private volatile Instant instant;

    private ChatbotTestClock() {
        reset();
    }

    public static ChatbotTestClock clock() {
        return INSTANCE;
    }

    public void reset() {
        setTo(START);
    }

    public void setTo(LocalDateTime kst) {
        instant = kst.atZone(CopyTimes.KST).toInstant();
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant, CopyTimes.KST);
    }

    @Override
    public ZoneId getZone() {
        return CopyTimes.KST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
