package com.hapbang.appearance.support;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.restassured.RestAssured;

/**
 * RestAssured가 이 테스트의 서버(랜덤 포트)로 요청하도록 테스트마다 포트를 맞춘다 (#390, {@link AppearanceE2ETest}에 등록).
 * 테스트 클래스마다 서버가 다를 수 있어서(컨텍스트가 다르면 포트도 다르다) 매번 현재 컨텍스트에서 읽는다.
 */
public class RestAssuredPortExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        String port = SpringExtension.getApplicationContext(context).getEnvironment()
                .getRequiredProperty("local.server.port");
        RestAssured.port = Integer.parseInt(port);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
