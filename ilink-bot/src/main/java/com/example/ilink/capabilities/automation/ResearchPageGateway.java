package com.example.ilink.capabilities.automation;

@FunctionalInterface
public interface ResearchPageGateway {
    String fetch(String url) throws Exception;
}
