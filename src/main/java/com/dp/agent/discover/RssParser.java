package com.dp.agent.discover;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 安全的 RSS 2.0 解析：禁用 DOCTYPE 与外部实体，只取展示需要的字段。 */
final class RssParser {

    record Item(String title, String link, String source, long publishedAt) {
    }

    private RssParser() {
    }

    static List<Item> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);

        Document document = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        NodeList nodes = document.getElementsByTagName("item");

        List<Item> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String title = childText(item, "title").trim();
            String link = childText(item, "link").trim();
            if (title.isEmpty() || link.isEmpty()) {
                continue;
            }
            items.add(new Item(title, link, childText(item, "source").trim(),
                    parseDate(childText(item, "pubDate"))));
        }
        return items;
    }

    private static String childText(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) {
            return "";
        }
        Node node = children.item(0);
        return node == null || node.getTextContent() == null ? "" : node.getTextContent();
    }

    private static long parseDate(String text) {
        try {
            return ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return Instant.now().toEpochMilli();
        }
    }
}
