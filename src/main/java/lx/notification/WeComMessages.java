package lx.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import com.alibaba.fastjson.JSONObject;

import lx.model.Zdm;

class WeComMessages {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

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

    static JSONObject links(List<Zdm> articles) {
        StringBuilder text = new StringBuilder("**本图商品详情链接（序号与图片对应）**\n");
        for (int i = 0; i < articles.size(); i++) {
            String url = articles.get(i).getUrl();
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
                throw new IllegalStateException("优惠详情链接缺失或不是HTTP地址");
            url = url.replace("\\", "%5C").replace(" ", "%20")
                    .replace("(", "%28").replace(")", "%29")
                    .replace("<", "%3C").replace(">", "%3E")
                    .replace("\r", "%0D").replace("\n", "%0A");
            text.append("[").append(i + 1).append(". 查看商品详情](").append(url).append(")\n");
        }
        if (text.toString().getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalStateException("企业微信商品链接消息超过4096字节限制");
        JSONObject content = new JSONObject();
        content.put("content", text.toString());
        return message("markdown", content);
    }

    private static JSONObject message(String type, JSONObject content) {
        JSONObject body = new JSONObject();
        body.put("msgtype", type);
        body.put(type, content);
        return body;
    }
}
