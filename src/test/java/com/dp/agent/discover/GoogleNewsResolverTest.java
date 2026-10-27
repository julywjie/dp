package com.dp.agent.discover;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleNewsResolverTest {

    @Test
    void parsesPublisherUrlFromBatchExecuteResponse() {
        String response = ")]}'\n\n"
                + "[[\"wrb.fr\",\"Fbv4je\","
                + "\"[\\\"garturlres\\\",\\\"https://k.sina.com.cn/article_1.html\\\",1]\","
                + "null,null,null,\"generic\"],[\"di\",10]]";

        assertEquals("https://k.sina.com.cn/article_1.html",
                GoogleNewsResolver.parseResolved(response));
    }

    @Test
    void identifiesGoogleNewsArticleLinks() {
        assertTrue(GoogleNewsResolver.isGoogleNewsArticle(
                URI.create("https://news.google.com/rss/articles/CBMi123?oc=5")));
        assertTrue(GoogleNewsResolver.isGoogleNewsArticle(
                URI.create("https://news.google.com/articles/CBMi123")));
        assertFalse(GoogleNewsResolver.isGoogleNewsArticle(
                URI.create("https://example.com/news.google.com/rss/articles/x")));
    }

    @Test
    void invalidResponseIsIgnored() {
        assertNull(GoogleNewsResolver.parseResolved(""));
        assertNull(GoogleNewsResolver.parseResolved(")]}'\n\nnot json"));
    }
}
