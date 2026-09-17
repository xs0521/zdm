package lx.notification;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;

import lx.model.Zdm;

import static lx.notification.WeComTestData.articles;
import static org.junit.Assert.*;

public class WeComImageBatchTest {
    @Test
    public void splitsOversizedImagesAndKeepsEachLinkWithItsImage() throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        List<Integer> renderedSizes = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            requests.add(JSONObject.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TableImageRenderer renderer = new TableImageRenderer() {
                @Override
                byte[] render(List<Zdm> articles) {
                    renderedSizes.add(articles.size());
                    return new byte[articles.size() > 1 ? WeComMessages.MAX_IMAGE_BYTES + 1 : 10];
                }
            };
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send");
            assertTrue(new WeComNotifier(uri, renderer).send(articles(2)));
            assertEquals(List.of(2, 1, 1), renderedSizes);
            assertEquals(4, requests.size());
            for (int i = 0; i < 2; i++) {
                assertEquals("image", requests.get(i * 2).getString("msgtype"));
                String links = requests.get(i * 2 + 1).getJSONObject("markdown").getString("content");
                assertTrue(links.contains("[1. 查看商品详情](https://www.smzdm.com/p/" + i + "/)"));
                assertFalse(links.contains("[2."));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void closesRendererWhenRenderingFailsBeforeSending() {
        AtomicBoolean closed = new AtomicBoolean();
        TableImageRenderer renderer = new TableImageRenderer() {
            @Override
            byte[] render(List<Zdm> articles) {
                throw new IllegalStateException("渲染失败");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        WeComNotifier notifier = new WeComNotifier(URI.create("http://127.0.0.1:1/send"), renderer);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> notifier.send(articles(1)));
        assertEquals("渲染失败", error.getMessage());
        assertTrue(closed.get());
    }

    @Test
    public void rejectsOversizedSingleArticleBeforeSending() {
        TableImageRenderer renderer = new TableImageRenderer() {
            @Override
            byte[] render(List<Zdm> articles) {
                return new byte[WeComMessages.MAX_IMAGE_BYTES + 1];
            }
        };
        WeComNotifier notifier = new WeComNotifier(URI.create("http://127.0.0.1:1/send"), renderer);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> notifier.send(articles(1)));
        assertTrue(error.getMessage().contains("2MB"));
    }
}
