package com.feijimiao.xianyuassistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码：给登录页加一道「人不是脚本」的门。
 *
 * <p>为什么需要：现有的失败锁定是按 IP 的（{@code AuthServiceImpl.MAX_LOGIN_ATTEMPTS=5}），
 * 攻击者换代理 IP 后每个 IP 只试一两次就绕过了——2026-11-17 那次爆破就是 95 次失败散在多个 IP 上。
 * 验证码不依赖 IP，每尝试一次都要过一次性票据，纯脚本成本直接抬高。</p>
 *
 * <p>存储：进程内 Map，5 分钟过期，一次性消费（验过即删）。多副本部署时每个副本各存各的，
 * 客户端拿到哪台的票就问哪台，够用；要跨副本共享再换 Redis。</p>
 */
@Slf4j
@Service
public class CaptchaService {

    /** 去掉 0/O/1/I 这些一眼分不清的字符，降低「人自己看错」的概率。 */
    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int CODE_LENGTH = 4;

    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    /** 内存上限：被刷时最多占这么多条，超了先清过期再按最旧淘汰。 */
    private static final int MAX_ENTRIES = 5000;

    private final SecureRandom random = new SecureRandom();

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String code, long expireAt) {
    }

    /** 发给前端的票据 + 图片（data URI 直接用）。 */
    public record Issued(String id, String image) {
    }

    public Issued issue() {
        evict();
        String code = randomCode();
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new Entry(code, System.currentTimeMillis() + TTL_MILLIS));
        return new Issued(id, "data:image/png;base64," + renderBase64(code));
    }

    /**
     * 校验并消费。空票/过期/错码一律 false；大小写不敏感。
     * 无论对错都把这条票删掉——一次性，避免拿同一张图反复猜。
     */
    public boolean verify(String id, String code) {
        if (id == null || id.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        Entry entry = store.remove(id);
        if (entry == null) {
            return false;
        }
        if (entry.expireAt() < System.currentTimeMillis()) {
            return false;
        }
        return entry.code().equalsIgnoreCase(code.trim());
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private void evict() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expireAt() < now) {
                it.remove();
            }
        }
        if (store.size() >= MAX_ENTRIES) {
            store.clear();
            log.warn("[captcha] store hit {} entries, cleared", MAX_ENTRIES);
        }
    }

    private String renderBase64(String code) {
        int width = 200;
        int height = 64;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 246, 248));
            g.fillRect(0, 0, width, height);

            // 干扰线
            for (int i = 0; i < 6; i++) {
                g.setColor(randomColor(150, 210));
                g.setStroke(new BasicStroke(1.2f));
                g.drawLine(random.nextInt(width), random.nextInt(height), random.nextInt(width), random.nextInt(height));
            }

            // 噪点
            for (int i = 0; i < 40; i++) {
                g.setColor(randomColor(160, 220));
                g.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
            }

            // 字符：逐个随机颜色 + 轻微旋转
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            for (int i = 0; i < code.length(); i++) {
                Color color = randomColor(30, 110);
                g.setColor(color);
                double angle = (random.nextDouble() - 0.5) * 0.5;
                int x = 18 + i * 44;
                int y = 46;
                AffineTransform old = g.getTransform();
                g.rotate(angle, x + 10, y - 12);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(old);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.warn("[captcha] render failed", e);
            return "";
        }
    }

    private Color randomColor(int min, int max) {
        int range = Math.max(1, max - min);
        return new Color(min + random.nextInt(range), min + random.nextInt(range), min + random.nextInt(range));
    }
}
