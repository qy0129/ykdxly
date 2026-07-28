package com.example.ilink.application.messaging;

/** Transport features that application workflows may safely use. */
public record ChannelCapabilities(boolean typing, boolean images, boolean files,
                                  boolean audio, boolean localFiles) {

    public static ChannelCapabilities wechat() {
        return new ChannelCapabilities(true, true, true, true, false);
    }

    public static ChannelCapabilities web() {
        return new ChannelCapabilities(true, true, true, true, true);
    }
}
