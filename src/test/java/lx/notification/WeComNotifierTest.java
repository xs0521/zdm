package lx.notification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;

import lx.model.Zdm;

import static org.junit.Assert.*;

public class WeComNotifierTest {
    private HttpServer server;
    private URI webhookUri;
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    private final List<Long> receivedAt = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
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
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
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
        assertFalse(new WeComNotifier(webhookUri).send(Collections.emptyList()));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void sendsNewsInBatchesOfEightAndPacesAcrossCalls() {
        WeComNotifier notifier = new WeComNotifier(webhookUri);
        assertTrue(notifier.send(articles(9)));
        assertTrue(notifier.send(articles(1)));
        assertEquals(3, requests.size());
        int[] sizes = {8, 1, 1};
        for (int i = 0; i < requests.size(); i++) {
            JSONObject request = requests.get(i);
            assertEquals("POST", methods.get(i));
            assertEquals("application/json; charset=UTF-8", contentTypes.get(i));
            assertEquals("news", request.getString("msgtype"));
            JSONArray items = request.getJSONObject("news").getJSONArray("articles");
            assertEquals(sizes[i], items.size());
            int start = i == 1 ? 8 : 0;
            for (int j = 0; j < items.size(); j++) {
                JSONObject item = items.getJSONObject(j);
                assertEquals("99元 | 商品\"好价\"😀" + (start + j), item.getString("title"));
                assertEquals("价格: 99元\n值/评论: 42/12\n平台: 京东", item.getString("description"));
                assertEquals("https://www.smzdm.com/p/" + (start + j) + "/", item.getString("url"));
                assertEquals("https://example.com/product.png", item.getString("picurl"));
            }
            if (i > 0)
                assertTrue(receivedAt.get(i) - receivedAt.get(i - 1) >= TimeUnit.SECONDS.toNanos(3));
        }
    }

    @Test
    public void rejectsHttpFailure() {
        status = 500;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WeComNotifier(webhookUri).send(articles(1)));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    public void stopsAfterApiFailureWithoutLeakingKey() {
        response = "{\"errcode\":93000,\"errmsg\":\"invalid key: test-secret\"}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WeComNotifier(webhookUri).send(articles(9)));
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
                    () -> new WeComNotifier(webhookUri).send(articles(1)));
            assertFalse(error.getMessage().contains("test-secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    public void rejectsNetworkFailureWithoutLeakingKey() {
        server.stop(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WeComNotifier(webhookUri).send(articles(1)));
        assertFalse(error.getMessage().contains("test-secret"));
        assertNull(error.getCause());
    }

    @Test
    public void preservesInterruptAndStopsSending() {
        WeComNotifier notifier = new WeComNotifier(webhookUri);
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

    private List<Zdm> articles(int count) {
        List<Zdm> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Zdm article = new Zdm();
            article.setTitle("商品\"好价\"😀" + i);
            article.setPrice("99元");
            article.setVoted("42");
            article.setComments("12");
            article.setArticleMall("京东");
            article.setUrl("https://www.smzdm.com/p/" + i + "/");
            article.setPicUrl("https://example.com/product.png");
            articles.add(article);
        }
        return articles;
    }
}
