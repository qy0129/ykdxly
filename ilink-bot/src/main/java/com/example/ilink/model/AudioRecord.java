package com.example.ilink.model;

public final class AudioRecord {

    private final AudioSource source;
    private final String path;
    private volatile String transcript;

    public AudioRecord(AudioSource source, String path, String transcript) {
        this.source = source;
        this.path = path;
        this.transcript = transcript;
    }

    public AudioSource source() {
        return source;
    }

    public String path() {
        return path;
    }

    public String transcript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }
}
