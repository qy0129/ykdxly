package com.example.ilink.application.messaging;

import java.util.Arrays;

/** SDK-neutral content carried by one inbound message. */
public sealed interface MessagePart
        permits MessagePart.Text, MessagePart.Image, MessagePart.Voice,
        MessagePart.File, MessagePart.Video {

    record Text(String text) implements MessagePart {
    }

    record Image(byte[] content, String fileName) implements MessagePart {
        public Image { content = copy(content); }
        @Override public byte[] content() { return copy(content); }
    }

    record Voice(byte[] content, String transcript, String fileName) implements MessagePart {
        public Voice { content = copy(content); }
        @Override public byte[] content() { return copy(content); }
    }

    record File(byte[] content, String fileName) implements MessagePart {
        public File { content = copy(content); }
        @Override public byte[] content() { return copy(content); }
    }

    record Video(byte[] content, String fileName) implements MessagePart {
        public Video { content = copy(content); }
        @Override public byte[] content() { return copy(content); }
    }

    private static byte[] copy(byte[] source) {
        return source == null ? new byte[0] : Arrays.copyOf(source, source.length);
    }
}
