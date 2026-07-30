package com.example.ilink.capabilities.automation;

import com.example.ilink.capabilities.web.SearchResult;

import java.util.List;

@FunctionalInterface
public interface SearchGateway {
    List<SearchResult> search(String query, int limit) throws Exception;
}
