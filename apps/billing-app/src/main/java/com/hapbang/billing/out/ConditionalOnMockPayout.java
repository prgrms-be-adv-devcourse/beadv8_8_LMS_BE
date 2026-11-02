package com.hapbang.billing.out;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

// 가상 송금(Mock 은행)이 켜졌을 때만 빈을 만든다 (#288). 조건은 MockPayoutActivation(설정 + 활성 프로필이 하나 이상이고 전부
// local·test. 프로필 미지정·기본 프로필·허용 목록 밖 프로필이 섞이면 꺼짐).
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnMockPayout.OnMockPayout.class)
public @interface ConditionalOnMockPayout {

    class OnMockPayout implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return MockPayoutActivation.isActive(context.getEnvironment());
        }
    }
}
