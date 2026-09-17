package lx.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;

import lx.model.Zdm;

public class WeComNotifier {
    private static final String WEBHOOK_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";
    private static final long SEND_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(3100);

    private final URI webhookUri;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long lastSentAt;
    private boolean sent;

    public WeComNotifier(String key) {
        this(StringUtils.isBlank(key) ? null
                : URI.create(WEBHOOK_URL + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8)));
    }

    WeComNotifier(URI webhookUri) {
        this.webhookUri = webhookUri;
    }

    public boolean send(List<Zdm> articles) {
        if (webhookUri == null) {
            System.out.println("企业微信推送未配置QW_WEBHOOK_KEY,将跳过");
            return false;
        }
        if (articles.isEmpty())
            return false;

        //企业微信图文消息每次最多8条,同一机器人每分钟最多20条消息。
        for (List<Zdm> part : Lists.partition(articles, 8)) {
            JSONObject news = new JSONObject();
            news.put("articles", part.stream().map(WeComNotifier::toNewsArticle).collect(Collectors.toList()));
            JSONObject body = new JSONObject();
            body.put("msgtype", "news");
            body.put("news", news);
            sendMessage(body);
        }
        return true;
    }

    private static JSONObject toNewsArticle(Zdm article) {
        JSONObject result = new JSONObject();
        result.put("title", article.getPrice() + " | " + article.getTitle());
        result.put("description", "价格: " + article.getPrice() + "\n值/评论: " + article.getVoted()
                + "/" + article.getComments() + "\n平台: " + article.getArticleMall());
        result.put("url", article.getUrl());
        result.put("picurl", article.getPicUrl());
        return result;
    }

    private void sendMessage(JSONObject body) {
        try {
            //同一个发送器跨商品批次复用,避免100条分批边界绕过限速。
            if (sent)
                TimeUnit.NANOSECONDS.sleep(Math.max(0, SEND_INTERVAL_NANOS - (System.nanoTime() - lastSentAt)));
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            lastSentAt = System.nanoTime();
            sent = true;
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("企业微信推送失败,HTTP状态码: " + response.statusCode());
            JSONObject result = JSONObject.parseObject(response.body());
            Integer code = result == null ? null : result.getInteger("errcode");
            if (!Integer.valueOf(0).equals(code))
                throw new IllegalStateException("企业微信推送失败,errcode: " + code);
            System.out.println("企业微信推送成功");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("企业微信推送被中断");
        } catch (IOException e) {
            //网络异常可能包含带key的请求地址,不输出原始异常或响应正文。
            throw new IllegalStateException("企业微信推送网络异常,请检查网络后重试");
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalStateException("企业微信推送失败,响应不是有效的JSON数据");
        }
    }
}
