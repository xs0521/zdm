package lx.notification;

import java.util.ArrayList;
import java.util.List;

import lx.model.Zdm;

class WeComTestData {
    static List<Zdm> articles(int count) {
        List<Zdm> articles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Zdm article = new Zdm();
            article.setTitle("商品\"好价\"😀" + i);
            article.setPrice("99元");
            article.setVoted("42");
            article.setComments("12");
            article.setArticleMall("京东");
            article.setUrl("https://www.smzdm.com/p/" + i + "/");
            article.setPicUrl("https://example.com/product.png");
            articles.add(article);
        }
        return articles;
    }
}
