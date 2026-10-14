package com.hapbang.customersupport.support;

import static org.mockito.Mockito.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.shared.lecture.port.LectureObjectionPort;

/**
 * 모듈 테스트용 {@link LectureObjectionPort}. 고객센터는 강의 모듈에 Gradle 의존을 걸지 않으므로 Mockito 목을 빈으로 둔다.
 * 직접 구현하지 않고 목을 쓰는 이유: 강의가 포트에 메서드를 추가해도 고객센터 테스트가 컴파일된다(#205).
 * 컨텍스트를 테스트끼리 공유하므로 쓰는 테스트는 매번 {@code reset}한다.
 */
@Configuration
public class LectureObjectionPortMockConfig {

    @Bean
    LectureObjectionPort lectureObjectionPort() {
        return mock(LectureObjectionPort.class);
    }
}
