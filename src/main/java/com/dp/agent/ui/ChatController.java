package com.dp.agent.ui;

import com.dp.agent.chat.Conversation;
import com.dp.agent.chat.UiMessage;
import com.dp.agent.config.AppConfig;
import com.dp.agent.config.ConfigStore;
import com.dp.agent.config.ProviderConfig;
import com.dp.agent.config.ProxySupport;
import com.dp.agent.config.ToolApprovalPolicy;
import com.dp.agent.core.AgentService;
import com.dp.agent.core.ModelFactory;
import com.dp.agent.discover.Article;
import com.dp.agent.discover.ArticleSummarizer;
import com.dp.agent.discover.HotListSummarizer;
import com.dp.agent.memory.MemoryService;
import com.dp.agent.session.SessionManager;
import javafx.collections.FXCollections;
import javafx.stage.Window;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 控制器：衔接 UI 事件、会话持久化与 Agent 后台执行。
 * Agent 回调全部来自工作线程，必须经 Platform.runLater 落 UI；
 * generation + sessionId 用于丢弃旧会话/旧轮次的迟到回调。
 */
public class ChatController implements ChatView.Callbacks {

    private final ConfigStore store;
    private final AppConfig config;
    private final MemoryService memory;
    private final AgentService agent;
    private final ToolApprovalCoordinator approvals;
    private final SessionCoordinator sessionCoordinator;
    private final HotListSummarizer listSummarizer;
    private final ArticleSummarizer articleSummarizer;
    private final DiscoverView discoverView;
    private final ProviderResolver providerResolver;
    private final Conversation conversation = new Conversation();
    private ChatView view;

    private long generation;
    private String runSessionId = "";
    private boolean awaitingCompletion;

    public ChatController(ConfigStore store, AppConfig config) throws IOException {
        this.store = store;
        this.config = config;
        this.providerResolver = new ProviderResolver(
                () -> view != null
                        ? view.providerBox().getSelectionModel().getSelectedItem()
                        : config.selected().orElse(null),
                store);
        this.memory = new MemoryService(store.getDir());
        this.agent = new AgentService(store.getDir(), memory);
        this.sessionCoordinator = new SessionCoordinator(
                new SessionManager(store.getDir()), conversation, agent,
                () -> view, this::discardInFlight, () -> generation++);
        this.listSummarizer = new HotListSummarizer();
        this.articleSummarizer = new ArticleSummarizer();
        this.discoverView = new DiscoverView(
                store.getDir(), this::summarizeList, this::summarizeArticle);
        this.approvals = new ToolApprovalCoordinator(this::ownerWindow);
    }

    private Window ownerWindow() {
        if (view == null || view.providerBox().getScene() == null) {
            return null;
        }
        return view.providerBox().getScene().getWindow();
    }

    /** 视图创建后注入并完成需要 view 的初始化。 */
    public void attach(ChatView view) {
        this.view = view;
        refreshProviderBox();
        view.setToolsEnabled(config.isToolsEnabled());
        view.setStreamingEnabled(config.isStreamingEnabled());
        view.setDarkTheme(config.isDarkTheme());
        view.sessionSidebar().setCallbacks(new SessionSidebar.Callbacks() {
            @Override
            public void onSelect(String id) {
                sessionCoordinator.open(id);
            }

            @Override
            public void onDelete(String id) {
                sessionCoordinator.delete(id);
            }

            @Override
            public void onNew() {
                onNewChat();
            }

            @Override
            public void onDiscover() {
                view.showDiscover();
                discoverView.activate();
            }
        });
        view.installDiscover(discoverView.node());
        sessionCoordinator.restoreInitial();
        sessionCoordinator.refreshSidebar();
        // 启动默认进入 AI 热榜
        view.showDiscover();
        discoverView.activate();
        String recovery = store.consumeRecoveryNote();
        if (recovery != null) {
            view.showToast(recovery, true);
        }
    }

    public void shutdown() {
        generation++; // 让所有仍在排队的回调失效
        if (view != null) {
            view.close();
        }
        approvals.close();
        discoverView.close();
        listSummarizer.close();
        articleSummarizer.close();
        if (awaitingCompletion) {
            conversation.rollbackLastUser();
            conversation.addError("生成已中断（应用关闭）");
            awaitingCompletion = false;
        }
        agent.close();
        memory.close();
        sessionCoordinator.close();
    }

    private void refreshProviderBox() {
        List<ProviderConfig> items = config.getProviders();
        view.providerBox().setItems(FXCollections.observableArrayList(items));
        config.selected().ifPresentOrElse(
                p -> view.providerBox().getSelectionModel().select(p),
                () -> {
                    if (!items.isEmpty()) {
                        view.providerBox().getSelectionModel().selectFirst();
                    }
                });
        view.providerBox().setOnAction(e ->
                onProviderChanged(view.providerBox().getSelectionModel().getSelectedItem()));
    }

    // ---- ChatView.Callbacks ----

    @Override
    public void onSend(String text) {
        if (agent.isBusy()) {
            view.showToast("正在生成回复，请稍候…", false);
            return;
        }
        ProviderResolver.Access access = providerResolver.resolve();
        if (!access.ok()) {
            showError(access.error());
            return;
        }
        ProviderConfig selected = access.provider();
        String apiKey = access.apiKey();

        // 先取快照再写入本轮：history 的契约是「不含当前输入」，由 AgentService 追加
        var history = conversation.historySnapshot();
        conversation.addUser(text);
        view.appendMessage(UiMessage.user(text), true);
        view.clearInput();
        sessionCoordinator.saveCurrent();          // 用户输入立即落盘，标题随之更新
        sessionCoordinator.refreshSidebar();
        view.setBusy(true, "Agent 思考中 …");

        generation++;
        runSessionId = sessionCoordinator.currentId();
        awaitingCompletion = true;
        AgentService.RunRequest request = new AgentService.RunRequest(
                selected, apiKey, view.isToolsEnabled(), config.isStreamingEnabled(),
                history, text, runSessionId, conversation.summary(), conversation.summarizedCount(),
                config.getToolApprovalPolicy() == ToolApprovalPolicy.ALWAYS, approvals.gate(), true);
        boolean accepted = agent.submit(request, uiListener(generation, runSessionId));
        if (!accepted) {
            awaitingCompletion = false;
            view.setBusy(false, "就绪");
            view.showToast("上一轮仍在退出，请稍后再试。", false);
        }
    }

    @Override
    public void onRetry() {
        if (agent.isBusy() || awaitingCompletion) {
            view.showToast("正在生成回复，请稍候…", false);
            return;
        }
        ProviderResolver.Access access = providerResolver.resolve();
        if (!access.ok()) {
            showError(access.error());
            return;
        }
        ProviderConfig selected = access.provider();
        String apiKey = access.apiKey();
        String prompt = conversation.prepareRetry();
        if (prompt == null) {
            view.showToast("没有可重新生成的一轮对话。", false);
            return;
        }
        view.setMessages(conversation.displaySnapshot());
        sessionCoordinator.saveCurrent();
        sessionCoordinator.refreshSidebar();
        view.setBusy(true, "重新生成中 …");

        generation++;
        runSessionId = sessionCoordinator.currentId();
        awaitingCompletion = true;
        AgentService.RunRequest request = new AgentService.RunRequest(
                selected, apiKey, view.isToolsEnabled(), config.isStreamingEnabled(),
                conversation.historySnapshot(), prompt, runSessionId,
                conversation.summary(), conversation.summarizedCount(),
                config.getToolApprovalPolicy() == ToolApprovalPolicy.ALWAYS, approvals.gate(), false);
        boolean accepted = agent.submit(request, uiListener(generation, runSessionId));
        if (!accepted) {
            awaitingCompletion = false;
            view.setBusy(false, "就绪");
            view.showToast("上一轮仍在退出，请稍后再试。", false);
        }
    }

    /** 发现页列表「总结」：解析当前提供商与 Key，再交给独立的后台总结服务。 */
    private CompletableFuture<HotListSummarizer.Result> summarizeList(String listName,
                                                                      List<Article> articles) {
        ProviderResolver.Access access = providerResolver.resolve();
        if (!access.ok()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(access.error()));
        }
        return listSummarizer.summarizeAsync(
                access.provider(), access.apiKey(), listName, articles);
    }

    /** 单篇文章总结：抓正文 + 模型概括；正文不可用时降级为标题概括。 */
    private CompletableFuture<ArticleSummarizer.Result> summarizeArticle(Article article) {
        ProviderResolver.Access access = providerResolver.resolve();
        if (!access.ok()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(access.error()));
        }
        return articleSummarizer.summarizeAsync(
                access.provider(), access.apiKey(), article);
    }

    @Override
    public void onStop() {
        approvals.cancelPending();
        if (agent.isBusy()) {
            // 立即反馈：在途 HTTP 请求可能要等到自身超时才真正退出。
            view.setBusy(true, "正在停止 …");
        }
        agent.cancel();
    }

    /** 与当前页面无关的错误：聊天流 + 顶部提示双通道，发现页也不会漏掉。 */
    private void showError(String message) {
        view.appendMessage(UiMessage.error(message), true);
        view.showToast(message, true);
    }

    @Override
    public void onNewChat() {
        sessionCoordinator.startNew();
    }

    @Override
    public void onSettings() {
        String oldProxy = proxySignature();
        boolean changed = SettingsDialog.show(view.providerBox().getScene().getWindow(),
                config, store);
        if (changed) {
            ProxySupport.apply(config);
            ModelFactory.clearCache();
            refreshProviderBox();
            if (!oldProxy.equals(proxySignature())) {
                view.showToast("代理设置已保存；如未生效请重启应用。", false);
            }
        }
    }

    private String proxySignature() {
        return config.getProxyMode() + "|" + config.getProxyHost() + "|" + config.getProxyPort();
    }

    @Override
    public void onFormatTool() {
        FormatDialog.show(view.providerBox().getScene().getWindow());
    }

    @Override
    public void onMemory() {
        MemoryDialog.show(ownerWindow(), memory);
    }

    @Override
    public void onSelectSession(String id) {
        sessionCoordinator.open(id);
    }

    @Override
    public void onDeleteSession(String id) {
        sessionCoordinator.delete(id);
    }

    @Override
    public void onProviderChanged(ProviderConfig selected) {
        if (selected == null) {
            return;
        }
        config.setSelectedProviderId(selected.getId());
        persist();
        view.setBusy(false, "已切换: " + selected.getDisplayName() + " · " + selected.getModelName());
    }

    @Override
    public void onToolsToggle(boolean enabled) {
        config.setToolsEnabled(enabled);
        persist();
    }

    @Override
    public void onStreamingToggle(boolean enabled) {
        config.setStreamingEnabled(enabled);
        persist();
    }

    @Override
    public void onThemeToggle(boolean dark) {
        config.setDarkTheme(dark);
        persist();
    }

    // ---- 会话生命周期 ----

    /** 中断在途任务；若尚未观察到完成，给旧会话补一条中断标记。 */
    private void discardInFlight(String reason) {
        if (awaitingCompletion) {
            conversation.rollbackLastUser();
            conversation.addError(reason);
            view.abortStreaming();
        }
        awaitingCompletion = false;
        approvals.cancelPending();
        if (agent.isBusy()) {
            agent.cancel();
        }
    }

    private void persist() {
        try {
            store.save(config);
        } catch (IOException e) {
            view.showToast("配置保存失败: " + e.getMessage(), true);
        }
    }

    // ---- Agent 回调（工作线程 → FX 线程） ----

    private AgentService.Listener uiListener(long gen, String sessionId) {
        return new AgentUiListener(new AgentUiListener.Host() {
            @Override
            public boolean active() {
                return gen == generation && sessionId.equals(sessionCoordinator.currentId());
            }

            @Override
            public void appendToken(String chunk) {
                view.appendToken(chunk);
            }

            @Override
            public void appendTrace(String event) {
                conversation.addTrace(event);
                view.appendTrace(event);
            }

            @Override
            public void compaction(String summary, int covered) {
                conversation.setCompaction(summary, covered);
                sessionCoordinator.saveCurrent();
            }

            @Override
            public void complete(String answer) {
                conversation.addAssistant(answer);
                view.finishStreaming(answer); // 流式气泡定格为最终答案，非流式则追加
                awaitingCompletion = false;
                sessionCoordinator.saveCurrent();
                sessionCoordinator.refreshSidebar();
                view.setBusy(false, "完成");
            }

            @Override
            public void error(String message) {
                conversation.rollbackLastUser(); // 失败轮不入上下文，重发即可
                view.abortStreaming();
                conversation.addError(message);
                view.appendMessage(UiMessage.error(message), true);
                view.showToast(message, true);
                awaitingCompletion = false;
                sessionCoordinator.saveCurrent();
                sessionCoordinator.refreshSidebar();
                view.setBusy(false, "出错，已恢复输入");
            }
        });
    }
}
