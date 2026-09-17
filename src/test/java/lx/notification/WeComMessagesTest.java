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
    public void escapesLinkDelimitersWithoutLosingUrlParameters() {
        List<Zdm> articles = articles(1);
        articles.get(0).setUrl("https://example.com/(优惠)?a=1&b=two words");
        String text = WeComMessages.links(articles).getJSONObject("markdown").getString("content");
        assertTrue(text.contains("[1. 查看商品详情](https://example.com/%28优惠%29?a=1&b=two%20words)"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    @Test
    public void rejectsMissingOrOversizedLinks() {
        List<Zdm> articles = articles(1);
        for (String url : new String[]{null, "", "javascript:alert(1)", "https://example.com/" + "a".repeat(4096)}) {
            articles.get(0).setUrl(url);
            assertThrows(IllegalStateException.class, () -> WeComMessages.links(articles));
        }
    }
}
