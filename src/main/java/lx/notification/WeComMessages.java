package lx.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSONObject;

import lx.model.Zdm;

class WeComMessages {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 2048;
    private static final String TEXT_HEADER = "本图商品详情（序号与图片对应）\n\n";

    static JSONObject image(byte[] image) {
        if (image.length == 0 || image.length > MAX_IMAGE_BYTES)
            throw new IllegalStateException("企业微信表格图片为空或超过2MB限制");
        JSONObject content = new JSONObject();
        content.put("base64", Base64.getEncoder().encodeToString(image));
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(image);
            StringBuilder md5 = new StringBuilder();
            for (byte value : digest)
                md5.append(String.format("%02x", value & 0xff));
            content.put("md5", md5.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算企业微信图片校验值");
        }
        return message("image", content);
    }

    static List<JSONObject> links(List<Zdm> articles) {
        List<JSONObject> messages = new ArrayList<>();
        StringBuilder text = new StringBuilder(TEXT_HEADER);
        int headerBytes = TEXT_HEADER.getBytes(StandardCharsets.UTF_8).length;
        int textBytes = headerBytes;
        for (int i = 0; i < articles.size(); i++) {
            String url = articles.get(i).getUrl();
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
                throw new IllegalStateException("优惠详情链接缺失或不是HTTP地址");
            String title = StringUtils.defaultString(articles.get(i).getTitle()).replace("\r", " ").replace("\n", " ");
            String entry = (i + 1) + ". " + title + "\n" + url + "\n\n";
            int entryBytes = entry.getBytes(StandardCharsets.UTF_8).length;
            if (headerBytes + entryBytes > MAX_TEXT_BYTES)
                throw new IllegalStateException("单条商品名称与链接超过企业微信文字消息2048字节限制");
            if (textBytes + entryBytes > MAX_TEXT_BYTES) {
                messages.add(textMessage(text.toString()));
                text = new StringBuilder(TEXT_HEADER);
                textBytes = headerBytes;
            }
            text.append(entry);
            textBytes += entryBytes;
        }
        if (textBytes > headerBytes)
            messages.add(textMessage(text.toString()));
        return messages;
    }

    private static JSONObject textMessage(String text) {
        JSONObject content = new JSONObject();
        content.put("content", text);
        return message("text", content);
    }

    private static JSONObject message(String type, JSONObject content) {
        JSONObject body = new JSONObject();
        body.put("msgtype", type);
        body.put(type, content);
        return body;
    }
}
