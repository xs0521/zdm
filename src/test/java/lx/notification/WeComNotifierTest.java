package lx.notification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
    public void sendsOnlyTextWithAllFieldsAndPacesAcrossCalls() {
        List<List<Zdm>> calls = List.of(articles(9), articles(1));
        WeComNotifier notifier = notifier();
        for (List<Zdm> articles : calls)
            assertTrue(notifier.send(articles));
        assertEquals(2, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            JSONObject request = requests.get(i);
            assertEquals("POST", methods.get(i));
            assertEquals("application/json; charset=UTF-8", contentTypes.get(i));
            assertEquals("text", request.getString("msgtype"));
            assertFalse(request.containsKey("image"));
            String content = request.getJSONObject("text").getString("content");
            for (Zdm article : calls.get(i)) {
                assertTrue(content.contains(article.getTitle() + "\n价格：99元\n赞评：42/12\n平台：京东\n链接："
                        + article.getUrl() + "\n\n"));
            }
            if (i > 0)
                assertTrue(receivedAt.get(i) - receivedAt.get(i - 1) >= TimeUnit.SECONDS.toNanos(3));
        }
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
                () -> notifier().send(articles(40)));
        assertTrue(error.getMessage().contains("93000"));
        assertFalse(error.getMessage().contains("test-secret"));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
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
            assertEquals(1, requests.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void stopsAfterAChunkIsRejected() {
        failAt = 2;
        status = 500;
        List<Zdm> articles = articles(3);
        articles.forEach(article -> article.setTitle("商品😀".repeat(150)));
        assertThrows(IllegalStateException.class, () -> notifier().send(articles));
        assertEquals(2, requests.size());
    }

    @Test
    public void validatesAllProductsBeforeSending() {
        List<Zdm> articles = articles(2);
        articles.get(1).setTitle("a".repeat(2048));
        assertThrows(IllegalStateException.class, () -> notifier().send(articles));
        assertTrue(requests.isEmpty());
    }

    private WeComNotifier notifier() {
        return new WeComNotifier(webhookUri);
    }

}
