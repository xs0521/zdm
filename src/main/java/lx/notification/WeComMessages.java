package lx.notification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSONObject;

import lx.model.Zdm;

class WeComMessages {
    private static final int MAX_TEXT_BYTES = 2048;

    static List<JSONObject> text(List<Zdm> articles) {
        List<JSONObject> messages = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int textBytes = 0;
        for (Zdm article : articles) {
            String url = article.getUrl();
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
                throw new IllegalStateException("优惠详情链接缺失或不是HTTP地址");
            String entry = singleLine(article.getTitle())
                    + "\n价格：" + singleLine(article.getPrice())
                    + "\n赞评：" + singleLine(article.getVoted()) + "/" + singleLine(article.getComments())
                    + "\n平台：" + singleLine(article.getArticleMall())
                    + "\n链接：" + url + "\n\n";
            int entryBytes = entry.getBytes(StandardCharsets.UTF_8).length;
            if (entryBytes > MAX_TEXT_BYTES)
                throw new IllegalStateException("单条商品详情超过企业微信文字消息2048字节限制");
            if (textBytes + entryBytes > MAX_TEXT_BYTES) {
                messages.add(textMessage(text.toString()));
                text = new StringBuilder();
                textBytes = 0;
            }
            text.append(entry);
            textBytes += entryBytes;
        }
        if (textBytes > 0)
            messages.add(textMessage(text.toString()));
        return messages;
    }

    private static JSONObject textMessage(String text) {
        JSONObject content = new JSONObject();
        content.put("content", text);
        JSONObject body = new JSONObject();
        body.put("msgtype", "text");
        body.put("text", content);
        return body;
    }

    private static String singleLine(String value) {
        return StringUtils.defaultString(value).replace("\r", " ").replace("\n", " ");
    }
}
