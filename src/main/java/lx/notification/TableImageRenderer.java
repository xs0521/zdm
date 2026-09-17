package lx.notification;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import lx.model.Zdm;

class TableImageRenderer implements AutoCloseable {
    private ChromeDriver driver;

    byte[] render(List<Zdm> articles) {
        if (driver == null) {
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                    "--disable-gpu", "--force-device-scale-factor=1", "--window-size=1200,1800");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));
        }
        String encoded = Base64.getEncoder().encodeToString(html(articles).getBytes(StandardCharsets.UTF_8));
        driver.get("data:text/html;charset=utf-8;base64," + encoded);
        //等待字体就绪后截图,保证中文文字排版稳定。
        driver.executeAsyncScript("const done = arguments[arguments.length - 1];"
                + "document.fonts.ready.then(() => done());");
        Rectangle rect = driver.findElement(By.tagName("main")).getRect();
        Map<String, Object> result = driver.executeCdpCommand("Page.captureScreenshot", Map.of(
                "format", "png", "captureBeyondViewport", true,
                "clip", Map.of("x", rect.x, "y", rect.y, "width", rect.width, "height", rect.height, "scale", 1)));
        return Base64.getDecoder().decode((String) result.get("data"));
    }

    static String html(List<Zdm> articles) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;background:#fff;color:#18283b;"
                + "font:22px 'Noto Sans CJK SC','PingFang SC','Microsoft YaHei',sans-serif}"
                + "main{width:1080px;padding:28px}h1{font-size:32px;margin:0 0 8px}"
                + ".note{margin:0 0 22px;color:#607086;font-size:18px}"
                + "table{width:100%;border-collapse:collapse;table-layout:fixed}"
                + "th{background:#203c58;color:#fff;text-align:left;font-size:20px}"
                + "th,td{padding:14px 12px;border:1px solid #dce4ed;overflow-wrap:anywhere}"
                + "td{vertical-align:middle;line-height:1.5}tr:nth-child(even){background:#f4f7fa}"
                + ".title{display:flex;gap:8px}.name{min-width:0}"
                + ".number{flex-shrink:0;font-weight:700;color:#426b94}.price{font-weight:700;color:#bb3c29}"
                + "</style></head><body><main><h1>什么值得买 · 优惠汇总</h1>"
                + "<p class='note'>本图共 " + articles.size() + " 条优惠 · 商品详情地址见后续文字消息（按序号对应）</p>"
                + "<table><colgroup><col style='width:58%'>"
                + "<col style='width:18%'><col style='width:12%'><col style='width:12%'></colgroup>"
                + "<thead><tr><th>标题</th><th>价格</th><th>赞 / 评</th><th>平台</th></tr></thead><tbody>");
        for (int i = 0; i < articles.size(); i++) {
            Zdm article = articles.get(i);
            html.append("<tr><td><div class='title'><span class='number'>").append(i + 1)
                    .append(".</span><span class='name'>").append(escape(article.getTitle()))
                    .append("</span></div></td><td class='price'>")
                    .append(escape(article.getPrice())).append("</td><td>")
                    .append(escape(article.getVoted())).append(" / ").append(escape(article.getComments()))
                    .append("</td><td>").append(escape(article.getArticleMall())).append("</td></tr>");
        }
        return html.append("</tbody></table></main></body></html>").toString();
    }

    private static String escape(String value) {
        return StringEscapeUtils.escapeHtml4(StringUtils.defaultString(value)).replace("'", "&#39;");
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }
}
