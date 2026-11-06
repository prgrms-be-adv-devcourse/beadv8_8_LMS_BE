package com.hapbang.coupon.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.hapbang.coupon.global.auth.LoginUserIdArgumentResolver;

/** 쿠폰 컨트롤러 파라미터 리졸버 등록. 강의의 리졸버는 강의 애노테이션만 처리하므로 둘이 겹치지 않는다 */
@Configuration(proxyBeanMethods = false)
public class CouponWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginUserIdArgumentResolver());
    }
}
