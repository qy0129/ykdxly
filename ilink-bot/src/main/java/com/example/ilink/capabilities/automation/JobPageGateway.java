package com.example.ilink.capabilities.automation;

@FunctionalInterface
public interface JobPageGateway {
    String fetch(String url) throws Exception;
}
