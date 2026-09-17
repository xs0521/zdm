package lx.notification;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.alibaba.fastjson.JSONObject;

import lx.model.Zdm;

import static lx.notification.WeComTestData.articles;
import static org.junit.Assert.*;

public class WeComMessagesTest {
    @Test
    public void skipsEmptyArticles() {
        assertTrue(WeComMessages.text(Collections.emptyList()).isEmpty());
    }

    @Test
    public void formatsAllProductDetailsAsPlainText() {
        List<Zdm> articles = articles(1);
        articles.get(0).setUrl("https://example.com/(优惠)?a=1&b=two words");
        JSONObject message = WeComMessages.text(articles).get(0);
        assertEquals("text", message.getString("msgtype"));
        String text = message.getJSONObject("text").getString("content");
        assertEquals("商品\"好价\"😀0\n价格：99元\n赞评：42/12\n平台：京东\n"
                + "链接：https://example.com/(优惠)?a=1&b=two words\n\n", text);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048);
    }

    @Test
    public void keepsTheTitleOnOneLine() {
        List<Zdm> articles = articles(1);
        articles.get(0).setTitle("商品\n名称\r促销");
        String text = WeComMessages.text(articles).get(0).getJSONObject("text").getString("content");
        assertTrue(text.startsWith("商品 名称 促销\n价格："));
    }

    @Test
    public void rejectsMissingOrOversizedLinks() {
        List<Zdm> articles = articles(1);
        for (String url : new String[]{null, "", "javascript:alert(1)", "https://example.com/" + "a".repeat(2048)}) {
            articles.get(0).setUrl(url);
            assertThrows(IllegalStateException.class, () -> WeComMessages.text(articles));
        }
    }

    @Test
    public void splitsUtf8TextWithoutBreakingProductDetails() {
        List<Zdm> articles = articles(3);
        articles.forEach(article -> article.setTitle("商品😀".repeat(150)));
        List<JSONObject> messages = WeComMessages.text(articles);
        assertEquals(3, messages.size());
        for (int i = 0; i < messages.size(); i++) {
            String text = messages.get(i).getJSONObject("text").getString("content");
            assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048);
            assertEquals(articles.get(i).getTitle() + "\n价格：99元\n赞评：42/12\n平台：京东\n链接："
                    + articles.get(i).getUrl() + "\n\n", text);
        }
    }

    @Test
    public void acceptsExactByteLimitAndRejectsOneByteOver() {
        List<Zdm> articles = articles(1);
        articles.get(0).setTitle("");
        String text = WeComMessages.text(articles).get(0).getJSONObject("text").getString("content");
        int remaining = 2048 - text.getBytes(StandardCharsets.UTF_8).length;
        articles.get(0).setTitle("a".repeat(remaining));
        assertEquals(2048, WeComMessages.text(articles).get(0).getJSONObject("text")
                .getString("content").getBytes(StandardCharsets.UTF_8).length);
        articles.get(0).setTitle("a".repeat(remaining + 1));
        assertThrows(IllegalStateException.class, () -> WeComMessages.text(articles));
    }
}
