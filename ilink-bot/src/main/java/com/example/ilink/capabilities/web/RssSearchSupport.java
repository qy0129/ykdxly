package com.example.ilink.capabilities.web;

import com.example.ilink.capabilities.web.SearchResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** RSS 搜索结果解析与公共链接过滤。 */
final class RssSearchSupport {

    private RssSearchSupport() {
    }

    static List<SearchResult> parse(String xml, int limit) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList items = document.getElementsByTagName("item");
            List<SearchResult> results = new ArrayList<>();
            for (int index = 0; index < items.getLength() && results.size() < limit; index++) {
                Element item = (Element) items.item(index);
                String url = text(item, "link");
                if (!isPublicHttpUrl(url)) continue;
                String title = cleanHtml(text(item, "title"));
                if (title.isBlank()) continue;
                String source = text(item, "source");
                if (source.isBlank()) source = host(url);
                results.add(new SearchResult(title, cleanHtml(text(item, "description")), source,
                        formatPublishedAt(text(item, "pubDate")), url));
            }
            return List.copyOf(results);
        } catch (Exception e) {
            throw new IOException("搜索结果解析失败", e);
        }
    }

    static boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
                return false;
            }
            String normalized = host.toLowerCase().replace("[", "").replace("]", "");
            if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                    || normalized.endsWith(".local") || normalized.equals("0.0.0.0")
                    || normalized.equals("::1") || normalized.startsWith("fc")
                    || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return false;
            return !normalized.matches("^10\\..*")
                    && !normalized.matches("^127\\..*")
                    && !normalized.matches("^169\\.254\\..*")
                    && !normalized.matches("^192\\.168\\..*")
                    && !isPrivate172(normalized);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isPrivate172(String host) {
        if (!host.matches("^172\\.\\d+\\..*")) return false;
        int second = Integer.parseInt(host.split("\\.")[1]);
        return second >= 16 && second <= 31;
    }

    private static String text(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String cleanHtml(String value) {
        return value.replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    private static String formatPublishedAt(String value) {
        if (value.isBlank()) return "";
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
