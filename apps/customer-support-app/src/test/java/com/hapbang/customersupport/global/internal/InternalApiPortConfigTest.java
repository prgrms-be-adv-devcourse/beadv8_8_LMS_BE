package com.hapbang.customersupport.global.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class InternalApiPortConfigTest {
    @Test
    void separatesRealListenersAndNormalizedPaths() throws Exception {
        int internalPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            internalPort = socket.getLocalPort();
        }
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        new InternalApiPortConfig().internalApiConnector(new InternalApiPortProperties(internalPort)).customize(factory);
        WebServer server = factory.getWebServer(context -> context.addServlet("probe", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(200);
            }
        }).addMapping("/"));
        try {
            server.start();
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertThat(status(client, server.getPort(), "/public")).isEqualTo(200);
                assertThat(status(client, server.getPort(), "/api/v1/internal/probe")).isEqualTo(404);
                assertThat(status(client, server.getPort(), "/api/v1/internal")).isEqualTo(404);
                assertThat(status(client, server.getPort(), "/api/v1/%69nternal/probe")).isEqualTo(404);
                assertThat(status(client, internalPort, "/api/v1/internal/probe")).isEqualTo(200);
                assertThat(status(client, internalPort, "/api/v1/internal")).isEqualTo(200);
                assertThat(status(client, internalPort, "/public")).isEqualTo(404);
                assertThat(status(client, internalPort, "/api/v1/internal-other")).isEqualTo(404);
                assertThat(status(client, internalPort, "/error")).isEqualTo(404);
            }
        } finally {
            server.stop();
            server.destroy();
        }
    }

    @Test
    void rejectsInvalidOrSharedPort() {
        assertThatThrownBy(() -> new InternalApiPortProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalApiPortProperties(65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalApiPortConfig()
                .internalApiConnector(new InternalApiPortProperties(8080))
                .customize(new TomcatServletWebServerFactory(8080)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int status(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Port", "8081").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
