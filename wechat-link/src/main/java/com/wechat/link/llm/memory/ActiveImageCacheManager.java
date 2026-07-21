package com.wechat.link.llm.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 激活图缓存管理器（方案 2 落地）
 * <p>
 * 缓存每个用户当前正在操作的最新原始图片二进制数据。
 * 当路由器判定用户意图为 IMAGE_EDIT 时，直接从此缓存提取原图送入编辑 API，
 * 而不依赖聊天历史队列中的 Base64 字符串。
 * </p>
 * <p>
 * 内存安全：对存入的 byte[] 大小进行校验，超过 MAX_IMAGE_SIZE 的图片会被拒绝。
 * </p>
 */
@Slf4j
@Component
public class ActiveImageCacheManager {

    /** 单张图片最大允许大小：5MB */
    private static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    /** 用户激活图缓存：userId → 当前正在操作的图片原始字节 */
    private final Map<String, byte[]> imageCache = new ConcurrentHashMap<>();

    /**
     * 更新用户的激活图缓存
     * <p>
     * 当微信传入新图、或文生图/P图模型生成新图时调用。
     * 超过 MAX_IMAGE_SIZE 的图片将被拒绝并记录警告日志。
     * </p>
     *
     * @param userId     用户 ID
     * @param imageBytes 图片原始字节数据
     */
    public void updateCache(String userId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[ImageCache] 用户 {} 尝试存入空图片，已忽略", userId);
            return;
        }
        if (imageBytes.length > MAX_IMAGE_SIZE) {
            log.warn("[ImageCache] 用户 {} 的图片大小 {}KB 超过限制 {}KB，已拒绝缓存",
                    userId, imageBytes.length / 1024, MAX_IMAGE_SIZE / 1024);
            return;
        }
        imageCache.put(userId, imageBytes);
        log.info("[ImageCache] 已更新用户 {} 的激活图缓存，大小: {}KB",
                userId, imageBytes.length / 1024);
    }

    /**
     * 获取用户当前缓存的激活图
     *
     * @param userId 用户 ID
     * @return 图片字节数据，无缓存时返回 null
     */
    public byte[] getCache(String userId) {
        return imageCache.get(userId);
    }

    /**
     * 判断用户是否有激活图缓存
     */
    public boolean hasCache(String userId) {
        return imageCache.containsKey(userId);
    }

    /**
     * 清除用户的激活图缓存
     */
    public void clearCache(String userId) {
        imageCache.remove(userId);
        log.info("[ImageCache] 已清除用户 {} 的激活图缓存", userId);
    }
}
