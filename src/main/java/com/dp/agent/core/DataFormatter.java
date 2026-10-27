package com.dp.agent.core;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * 结构化文本格式化：自动识别 JSON / XML 并缩进排版。
 * 纯逻辑，无 UI / 无 IO，供格式化工具体验与 Agent 工具共用。
 * XML 解析禁用 DOCTYPE 与外部实体（防 XXE）。
 */
public final class DataFormatter {

    public enum Kind {
        JSON, XML
    }

    public record Result(String output, Kind kind) {
    }

    /** 格式化失败，message 面向用户，含行列定位。 */
    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Jackson 默认输出 "key" : value；改成更常见的 "key": value。 */
    private static final ObjectWriter JSON_PRETTY = JSON.writer(new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    private DataFormatter() {
    }

    /** 按首个非空白字符判断类型：{ [ → JSON，< → XML。 */
    public static Kind detect(String text) throws FormatException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '{' || c == '[') {
                return Kind.JSON;
            }
            if (c == '<') {
                return Kind.XML;
            }
            throw new FormatException("无法识别格式：内容应以 {、[（JSON）或 <（XML）开头。");
        }
        throw new FormatException("内容为空：请粘贴 JSON 或 XML 文本。");
    }

    public static Result format(String text) throws FormatException {
        Kind kind = detect(text);
        return new Result(kind == Kind.JSON ? formatJson(text) : formatXml(text), kind);
    }

    public static String formatJson(String text) throws FormatException {
        try {
            return JSON_PRETTY.writeValueAsString(tree(text));
        } catch (JsonProcessingException e) {
            throw new FormatException(jsonError(e));
        }
    }

    public static String minifyJson(String text) throws FormatException {
        try {
            return JSON.writeValueAsString(tree(text));
        } catch (JsonProcessingException e) {
            throw new FormatException(jsonError(e));
        }
    }

    private static JsonNode tree(String text) throws JsonProcessingException, FormatException {
        JsonNode node = JSON.readTree(text);
        if (node == null || node.isMissingNode()) {
            throw new FormatException("内容为空：请粘贴有效的 JSON 文本。");
        }
        return node;
    }

    private static String jsonError(JsonProcessingException e) {
        JsonLocation location = e.getLocation();
        String position = location == null ? ""
                : "第 " + location.getLineNr() + " 行第 " + location.getColumnNr() + " 列: ";
        return "JSON 解析失败，" + position + e.getOriginalMessage();
    }

    public static String formatXml(String text) throws FormatException {
        try {
            Document document = parseXml(text);
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            // 已有缩进文本节点时转换器可能补出空行，去掉纯空行不影响内容
            String output = writer.toString().replaceAll("(?m)^[ \\t]*\\r?\\n", "").trim();
            return output.replaceFirst(" standalone=\"no\"", "");
        } catch (SAXParseException e) {
            String hint = e.getMessage() != null && e.getMessage().contains("DOCTYPE")
                    ? "（出于安全考虑，暂不支持 DOCTYPE / 外部实体）" : "";
            throw new FormatException("XML 解析失败，第 " + e.getLineNumber()
                    + " 行第 " + e.getColumnNumber() + " 列: " + e.getMessage() + hint);
        } catch (Exception e) {
            throw new FormatException("XML 解析失败: " + e.getMessage());
        }
    }

    private static Document parseXml(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new StrictErrorHandler());
        return builder.parse(new InputSource(new StringReader(text)));
    }

    /** 把 warning 也提升为解析失败，避免"看似成功但内容有丢失"。 */
    private static final class StrictErrorHandler implements org.xml.sax.ErrorHandler {
        @Override
        public void warning(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    }
}
