package com.cloudwithvarjosh;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

public class AppTest {

    @Test
    void testBrandHtmlContainsBrand() {
        String h = App.brandHtml();
        assertNotNull(h);
        assertTrue(h.contains("Cloud With VarJosh"));
    }

    @Test
    void testBrandHtmlIsNotEmpty() {
        String h = App.brandHtml();
        assertTrue(h.length() > 0);
    }

    @Test
    void testFormatGreetingRegularUser() {
        String greeting = App.formatGreeting("guest");
        assertEquals("Hello, guest", greeting);
    }

    @Test
    void testFormatGreetingAdminLiteral() {
        String greeting = App.formatGreeting("admin");
        assertEquals("Welcome, admin!", greeting);
    }

    @Test
    void testResolveUsernameWithInput() {
        ByteArrayInputStream in = new ByteArrayInputStream("john\n".getBytes());
        String username = App.resolveUsername(in);
        assertEquals("john", username);
    }

    @Test
    void testResolveUsernameEmptyInput() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        String username = App.resolveUsername(in);
        assertEquals("guest", username);
    }

    @Test
    void testGetApiKey() {
        assertEquals("CVWJ_DEMO_SECRET", App.getApiKey());
    }

    @Test
    void testAppInstantiation() {
        App app = new App();
        assertNotNull(app);
    }

    @Test
    void testHttpServerRootEndpoint() throws IOException, InterruptedException {
        HttpServer server = App.createServer(0);
        server.start();
        int boundPort = server.getAddress().getPort();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + boundPort + "/"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Cloud With VarJosh"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testMainMethodExecution() throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("admin\n".getBytes()));
            // Pass "0" as argument to bind ephemeral port
            App.main(new String[]{"0"});
            assertNotNull(App.runningServer);
        } finally {
            App.stopServer();
            System.setIn(originalIn);
        }
    }
}
