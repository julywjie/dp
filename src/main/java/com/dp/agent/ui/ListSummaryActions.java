package com.dp.agent.ui;

import com.dp.agent.core.Errors;
import com.dp.agent.discover.Article;
import com.dp.agent.discover.HotListSummarizer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 发现页多个「总结」按钮共享的交互状态：
 * 同一时刻只允许一次总结，执行中禁用全部按钮，完成后恢复并在弹窗里展示结果。
 */
final class ListSummaryActions {

    private static final Logger log = LoggerFactory.getLogger(ListSummaryActions.class);

    private record Binding(Button button, String listName,
                           Supplier<List<Article>> articles, Label status, SummaryCard card) {
    }

    private final DiscoverView.Summarizer summarizer;
    private final List<Binding> bindings = new ArrayList<>();
    private boolean busy;
    private boolean closed;

    ListSummaryActions(DiscoverView.Summarizer summarizer) {
        this.summarizer = summarizer;
    }

    void bind(Button button, String listName,
              Supplier<List<Article>> articles, Label status, SummaryCard card) {
        button.setText("总结");
        button.setGraphic(Icons.icon(Icons.SPARK, 13));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(5);
        button.getStyleClass().add("ghost-button");
        button.setTooltip(new Tooltip("用当前模型总结本页内容（简洁要点）"));
        button.setAccessibleText("总结" + listName);
        Binding binding = new Binding(button, listName, articles, status, card);
        button.setOnAction(e -> summarize(binding));
        bindings.add(binding);
    }

    private void summarize(Binding binding) {
        if (busy) {
            return;
        }
        List<Article> articles = binding.articles().get();
        if (articles == null || articles.isEmpty()) {
            binding.status().setText("暂无可总结的内容，请先刷新");
            return;
        }
        busy = true;
        bindings.forEach(b -> b.button().setDisable(true));
        binding.button().setText("总结中…");
        binding.card().showLoading(
                binding.listName() + " · 正在总结 " + articles.size() + " 条",
                "正在生成简洁要点…");

        CompletableFuture<HotListSummarizer.Result> future;
        try {
            future = summarizer.summarize(binding.listName(), List.copyOf(articles));
        } catch (RuntimeException e) {
            finish(binding, null, e);
            return;
        }
        future.whenComplete((result, error) -> Platform.runLater(
                () -> finish(binding, result, error)));
    }

    private void finish(Binding binding, HotListSummarizer.Result result, Throwable error) {
        if (closed) {
            return;
        }
        busy = false;
        bindings.forEach(b -> {
            b.button().setDisable(false);
            b.button().setText("总结");
        });
        if (error != null) {
            String message = Errors.userMessage(error, "模型调用失败，请稍后重试。", 300);
            log.warn("列表总结失败 list={}: {}", binding.listName(), message, error);
            binding.card().showError("总结失败", message);
            return;
        }
        if (result == null || result.text() == null || result.text().isBlank()) {
            log.warn("列表总结为空 list={}", binding.listName());
            binding.card().showError("总结失败", "模型未返回总结内容，请重试。");
            return;
        }
        log.info("列表总结完成 list={} articles={} chars={}",
                binding.listName(), result.articleCount(), result.text().length());
        binding.card().showResult(
                binding.listName() + " · 基于当前 " + result.articleCount() + " 条",
                result.text(), result.modelName());
    }

    void close() {
        closed = true;
    }
}
