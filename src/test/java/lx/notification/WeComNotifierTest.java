package lx.notification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;

import lx.model.Zdm;

import static org.junit.Assert.*;
import static lx.notification.WeComTestData.articles;

public class WeComNotifierTest {
    private HttpServer server;
    private URI webhookUri;
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final List<Long> receivedAt = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final List<List<Zdm>> rendered = new ArrayList<>();
    private final byte[] image = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");
    private int closeCount;
    private volatile int failAt = 1;
    private volatile int status = 200;
    private volatile String response = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/webhook/send", exchange -> {
            receivedAt.add(System.nanoTime());
            methods.add(exchange.getRequestMethod());
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            requests.add(JSONObject.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            boolean failed = requests.size() >= failAt;
            byte[] body = (failed ? response : "{\"errcode\":0}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failed ? status : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        webhookUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/cgi-bin/webhook/send?key=test-secret");
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void skipsMissingOrBlankKey() {
        for (String key : new String[]{null, "", " \t\n"})
            assertFalse(new WeComNotifier(key).send(articles(1)));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void skipsEmptyArticles() {
        assertFalse(notifier().send(Collections.emptyList()));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void sendsTableImagesWithMatchingLinksAndPacesAcrossCalls() {
        WeComNotifier notifier = notifier();
        assertTrue(notifier.send(articles(9)));
        assertTrue(notifier.send(articles(1)));
        assertEquals(6, requests.size());
        assertEquals(3, rendered.size());
        int[] sizes = {8, 1, 1};
        for (int i = 0; i < requests.size(); i++) {
            JSONObject request = requests.get(i);
            assertEquals("POST", methods.get(i));
            assertEquals("application/json; charset=UTF-8", contentTypes.get(i));
            assertEquals(i % 2 == 0 ? "image" : "markdown", request.getString("msgtype"));
            if (i % 2 == 0) {
                assertArrayEquals(image, Base64.getDecoder().decode(request.getJSONObject("image").getString("base64")));
                assertEquals(sizes[i / 2], rendered.get(i / 2).size());
            } else {
                String links = request.getJSONObject("markdown").getString("content");
                int start = i / 2 == 1 ? 8 : 0;
                for (int j = 0; j < sizes[i / 2]; j++) {
                    assertEquals("商品\"好价\"😀" + (start + j), rendered.get(i / 2).get(j).getTitle());
                    assertTrue(links.contains("[" + (j + 1) + ". 查看商品详情](https://www.smzdm.com/p/" + (start + j) + "/)"));
                }
            }
            if (i > 0)
                assertTrue(receivedAt.get(i) - receivedAt.get(i - 1) >= TimeUnit.SECONDS.toNanos(3));
        }
        assertEquals(2, closeCount);
    }

    @Test
    public void rejectsHttpFailure() {
        status = 500;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> notifier().send(articles(1)));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    public void stopsAfterApiFailureWithoutLeakingKey() {
        response = "{\"errcode\":93000,\"errmsg\":\"invalid key: test-secret\"}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> notifier().send(articles(9)));
        assertTrue(error.getMessage().contains("93000"));
        assertFalse(error.getMessage().contains("test-secret"));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
        assertEquals(1, closeCount);
    }

    @Test
    public void rejectsMissingCodeAndMalformedResponses() {
        for (String body : new String[]{"{}", "null", "not JSON test-secret", "{\"errcode\":\"test-secret\"}"}) {
            response = body;
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> notifier().send(articles(1)));
            assertFalse(error.getMessage().contains("test-secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    public void rejectsNetworkFailureWithoutLeakingKey() {
        server.stop(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> notifier().send(articles(1)));
        assertFalse(error.getMessage().contains("test-secret"));
        assertNull(error.getCause());
    }

    @Test
    public void preservesInterruptAndStopsSending() {
        WeComNotifier notifier = notifier();
        assertTrue(notifier.send(articles(1)));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> notifier.send(articles(1)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, requests.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void failsWhenLinkMessageIsRejectedAfterImageSuccess() {
        failAt = 2;
        status = 500;
        assertThrows(IllegalStateException.class, () -> notifier().send(articles(9)));
        assertEquals(2, requests.size());
        assertEquals(1, rendered.size());
        assertEquals(1, closeCount);
    }

    private WeComNotifier notifier() {
        return new WeComNotifier(webhookUri, new TableImageRenderer() {
            @Override
            byte[] render(List<Zdm> articles) {
                rendered.add(new ArrayList<>(articles));
                return image;
            }

            @Override
            public void close() {
                closeCount++;
            }
        });
    }

}
