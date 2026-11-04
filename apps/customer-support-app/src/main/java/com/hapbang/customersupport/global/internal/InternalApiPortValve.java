package com.hapbang.customersupport.global.internal;

import java.io.IOException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import jakarta.servlet.ServletException;

/** Tomcat이 정규화·decode한 경로와 실제 수신 포트를 검사한다. forwarded 헤더를 신뢰하지 않는다. */
final class InternalApiPortValve extends ValveBase {
    private static final String PREFIX = "/api/v1/internal";
    private final int internalPort;

    InternalApiPortValve(int internalPort) {
        super(true);
        this.internalPort = internalPort;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        String path = request.getRequestPathMB().toString();
        boolean internalPath = path.equals(PREFIX) || path.startsWith(PREFIX + "/");
        if ((request.getLocalPort() == internalPort) != internalPath) {
            response.setStatus(404);
            return;
        }
        getNext().invoke(request, response);
    }
}
