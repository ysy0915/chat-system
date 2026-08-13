package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * 多模态消息的内容部分 — text 或 image_url
 */
@Schema(description = "多模态内容部分")
public class ContentPart {

    @Schema(description = "类型: text / image_url")
    private String type;
    @Schema(description = "文本内容 (type=text时)")
    private String text;
    @Schema(description = "图片URL (type=image_url时)")
    private ImageUrl imageUrl;

    public ContentPart() {}

    public ContentPart(String type) {
        this.type = type;
    }

    // ========= 静态工厂 =========

    public static ContentPart text(String text) {
        ContentPart p = new ContentPart("text");
        p.text = text;
        return p;
    }

    public static ContentPart imageUrl(String url) {
        ContentPart p = new ContentPart("image_url");
        p.imageUrl = new ImageUrl(url);
        return p;
    }

    // ========= 转换 =========

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        if ("text".equals(type)) {
            map.put("text", text);
        } else if ("image_url".equals(type) && imageUrl != null) {
            map.put("image_url", imageUrl.toMap());
        }
        return map;
    }

    // ========= getters / setters =========

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public ImageUrl getImageUrl() { return imageUrl; }
    public void setImageUrl(ImageUrl imageUrl) { this.imageUrl = imageUrl; }

    // ========= 内部类 =========

    @Schema(description = "图片URL封装")
    public static class ImageUrl {
        @Schema(description = "图片URL", example = "https://example.com/img.png")
        private String url;

        public ImageUrl() {}
        public ImageUrl(String url) { this.url = url; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public Map<String, String> toMap() {
            Map<String, String> m = new HashMap<>();
            m.put("url", url);
            return m;
        }
    }
}
