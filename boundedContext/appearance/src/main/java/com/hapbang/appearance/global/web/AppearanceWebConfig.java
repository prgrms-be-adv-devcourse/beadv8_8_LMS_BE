package com.hapbang.appearance.global.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link LoginMemberArgumentResolver}를 Spring MVC에 등록한다.
 * {@code @LoginMember}가 붙은 파라미터에만 동작하므로 다른 모듈의 컨트롤러에는 영향이 없다.
 */
@Configuration(proxyBeanMethods = false)
public class AppearanceWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginMemberArgumentResolver());
    }
}
