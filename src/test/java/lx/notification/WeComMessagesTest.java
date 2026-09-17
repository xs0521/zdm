package lx.notification;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Test;

import com.alibaba.fastjson.JSONObject;

import lx.model.Zdm;

import static lx.notification.WeComTestData.articles;
import static org.junit.Assert.*;

public class WeComMessagesTest {
    @Test
    public void encodesOriginalBytesAndUsesTheirMd5() {
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        JSONObject body = WeComMessages.image(bytes);
        assertEquals("image", body.getString("msgtype"));
        assertArrayEquals(bytes, Base64.getDecoder().decode(body.getJSONObject("image").getString("base64")));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", body.getJSONObject("image").getString("md5"));
    }

    @Test
    public void enforcesImageSizeBeforeSending() {
        assertThrows(IllegalStateException.class, () -> WeComMessages.image(new byte[0]));
        assertThrows(IllegalStateException.class,
                () -> WeComMessages.image(new byte[2 * 1024 * 1024 + 1]));
    }

    @Test
    public void sendsProductNamesAndOriginalUrlsAsPlainText() {
        List<Zdm> articles = articles(1);
        articles.get(0).setUrl("https://example.com/(优惠)?a=1&b=two words");
        JSONObject message = WeComMessages.links(articles).get(0);
        assertEquals("text", message.getString("msgtype"));
        String text = message.getJSONObject("text").getString("content");
        assertTrue(text.contains("1. 商品\"好价\"😀0\nhttps://example.com/(优惠)?a=1&b=two words\n"));
        assertFalse(text.contains("]("));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048);
    }

    @Test
    public void rejectsMissingOrOversizedLinks() {
        List<Zdm> articles = articles(1);
        for (String url : new String[]{null, "", "javascript:alert(1)", "https://example.com/" + "a".repeat(2048)}) {
            articles.get(0).setUrl(url);
            assertThrows(IllegalStateException.class, () -> WeComMessages.links(articles));
        }
    }

    @Test
    public void splitsUtf8TextWithoutBreakingProductsOrResettingNumbers() {
        List<Zdm> articles = articles(3);
        articles.forEach(article -> article.setTitle("商品😀".repeat(150)));
        List<JSONObject> messages = WeComMessages.links(articles);
        assertEquals(3, messages.size());
        for (int i = 0; i < messages.size(); i++) {
            String text = messages.get(i).getJSONObject("text").getString("content");
            assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048);
            assertTrue(text.contains((i + 1) + ". " + articles.get(i).getTitle() + "\n" + articles.get(i).getUrl() + "\n"));
        }
    }

    @Test
    public void acceptsExactByteLimitAndRejectsOneByteOver() {
        List<Zdm> articles = articles(1);
        articles.get(0).setTitle("");
        String text = WeComMessages.links(articles).get(0).getJSONObject("text").getString("content");
        int remaining = 2048 - text.getBytes(StandardCharsets.UTF_8).length;
        articles.get(0).setTitle("a".repeat(remaining));
        assertEquals(2048, WeComMessages.links(articles).get(0).getJSONObject("text")
                .getString("content").getBytes(StandardCharsets.UTF_8).length);
        articles.get(0).setTitle("a".repeat(remaining + 1));
        assertThrows(IllegalStateException.class, () -> WeComMessages.links(articles));
    }
}
