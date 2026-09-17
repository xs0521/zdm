package lx.notification;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Assume;
import org.junit.Test;

import lx.model.Zdm;

import static lx.notification.WeComTestData.articles;
import static org.junit.Assert.*;

public class TableImageRendererTest {
    @Test
    public void keepsFiveColumnsAndEscapesProductTextAndImageAttributes() {
        List<Zdm> articles = articles(1);
        articles.get(0).setTitle("<script>alert('标题')</script> & 商品");
        articles.get(0).setPicUrl("https://example.com/' onerror='alert(1)");
        String html = TableImageRenderer.html(articles);
        assertTrue(html.contains("<th>图片</th><th>标题</th><th>价格</th><th>赞 / 评</th><th>平台</th>"));
        assertTrue(html.contains("<span class='number'>1.</span>"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;标题&#39;)&lt;/script&gt; &amp; 商品"));
        assertTrue(html.contains("src='https://example.com/&#39; onerror=&#39;alert(1)'"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    public void handlesMissingPicturesAndProtocolRelativeUrls() {
        List<Zdm> articles = articles(2);
        articles.get(0).setPicUrl(null);
        articles.get(1).setPicUrl("//example.com/product.png");
        String html = TableImageRenderer.html(articles);
        assertTrue(html.contains("暂无图片"));
        assertTrue(html.contains("src='https://example.com/product.png'"));
        assertTrue(html.contains("本图共 2 条优惠"));
    }

    @Test
    public void rendersCompleteReadablePngWithChrome() throws Exception {
        Assume.assumeTrue("使用-Dwecom.render.test=true运行浏览器截图测试", Boolean.getBoolean("wecom.render.test"));
        List<Zdm> articles = articles(8);
        String[] titles = {"真无线蓝牙耳机 降噪通话 长续航（示例）", "抽纸 3层 100抽 24包 家庭装（示例）",
                "全自动咖啡机 家用意式浓缩 限时优惠（示例）", "运动休闲鞋 男女同款 多色可选（示例）",
                "27英寸显示器 4K超高清 旋转升降支架（示例）", "纯牛奶 250mL×24盒 早餐营养装（示例）",
                "儿童绘本套装 启蒙阅读 礼盒装（示例）", "轻薄羽绒服 秋冬保暖 防风 连帽（示例）"};
        for (int i = 0; i < articles.size(); i++) {
            articles.get(i).setTitle(titles[i]);
            articles.get(i).setPrice(i % 2 == 0 ? "99元（需用券）" : "59.9元");
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='108' height='108'>"
                    + "<rect width='108' height='108' rx='12' fill='#e8eff7'/>"
                    + "<rect x='24' y='18' width='60' height='72' rx='8' fill='#7392b5'/>"
                    + "<circle cx='54' cy='48' r='16' fill='#fff'/></svg>";
            articles.get(i).setPicUrl("data:image/svg+xml;base64,"
                    + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8)));
        }
        try (TableImageRenderer renderer = new TableImageRenderer()) {
            byte[] png = renderer.render(articles);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(decoded);
            assertEquals(1080, decoded.getWidth());
            assertTrue(decoded.getHeight() > 1000);
            assertTrue(png.length <= WeComMessages.MAX_IMAGE_BYTES);
            Files.createDirectories(Path.of("target", "wecom-preview"));
            Files.write(Path.of("target", "wecom-preview", "table.png"), png);
            articles.get(7).setTitle("很长的商品标题，需要完整显示。".repeat(100));
            articles.get(7).setPicUrl("data:image/png;base64,broken");
            BufferedImage tall = ImageIO.read(new ByteArrayInputStream(renderer.render(articles)));
            assertTrue(tall.getHeight() > 1800);
            //复用同一浏览器,检查短表格不会残留上一组的行。
            BufferedImage small = ImageIO.read(new ByteArrayInputStream(renderer.render(articles.subList(0, 1))));
            assertTrue(small.getHeight() < decoded.getHeight());
        }
    }
}
