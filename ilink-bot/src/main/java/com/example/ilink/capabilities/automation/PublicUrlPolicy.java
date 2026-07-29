package com.example.ilink.capabilities.automation;

import java.net.InetAddress;
import java.net.URI;

/** 防止自动化网页工具访问本机、局域网和非 HTTP 资源。 */
public final class PublicUrlPolicy {
    private PublicUrlPolicy() { }

    public static URI requirePublic(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("只允许公开 HTTP/HTTPS 地址");
            }
            String host = uri.getHost();
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".local")) {
                throw new IllegalArgumentException("不允许访问本机或局域网地址");
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("不允许访问本机或局域网地址");
                }
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("网址无法安全解析", error);
        }
    }
}
