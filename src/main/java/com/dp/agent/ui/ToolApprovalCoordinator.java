package com.dp.agent.ui;

import com.dp.agent.core.tool.ToolApprovalGate;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 工具审批桥：Agent 工作线程阻塞等待，弹窗在 FX 线程以非模态方式展示，
 * 保证「停止」按钮和会话切换始终可用。超时/取消都按拒绝处理。
 */
public final class ToolApprovalCoordinator {

    private static final long TIMEOUT_SECONDS = 120;

    private final Supplier<Window> owner;
    private final AtomicReference<CompletableFuture<ToolApprovalGate.Decision>> pending =
            new AtomicReference<>();
    private final AtomicReference<Stage> window = new AtomicReference<>();

    public ToolApprovalCoordinator(Supplier<Window> owner) {
        this.owner = owner;
    }

    public ToolApprovalGate gate() {
        return this::request;
    }

    private ToolApprovalGate.Decision request(ToolApprovalGate.ApprovalRequest request)
            throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            return ToolApprovalGate.Decision.DENIED; // 不能在 FX 线程上等待自己
        }
        CompletableFuture<ToolApprovalGate.Decision> future = new CompletableFuture<>();
        if (!pending.compareAndSet(null, future)) {
            return ToolApprovalGate.Decision.CANCELLED;
        }
        Platform.runLater(() -> show(request, future));
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.complete(ToolApprovalGate.Decision.TIMEOUT);
            closeWindow();
            return ToolApprovalGate.Decision.TIMEOUT;
        } catch (InterruptedException e) {
            future.complete(ToolApprovalGate.Decision.CANCELLED);
            closeWindow();
            throw e;
        } catch (ExecutionException e) {
            return ToolApprovalGate.Decision.DENIED;
        } finally {
            pending.compareAndSet(future, null);
        }
    }

    /** 停止/切换会话/关闭应用时调用：直接按取消处理并关闭弹窗。 */
    public void cancelPending() {
        CompletableFuture<ToolApprovalGate.Decision> future = pending.getAndSet(null);
        if (future != null) {
            future.complete(ToolApprovalGate.Decision.CANCELLED);
        }
        closeWindow();
    }

    public void close() {
        cancelPending();
    }

    private void show(ToolApprovalGate.ApprovalRequest request,
                      CompletableFuture<ToolApprovalGate.Decision> future) {
        if (future.isDone()) {
            return;
        }
        Stage stage = new Stage();
        Window ownerWindow = owner.get();
        if (ownerWindow != null) {
            stage.initOwner(ownerWindow);
        }
        stage.initModality(Modality.NONE);
        stage.setTitle("工具调用确认");
        stage.setResizable(false);

        Label title = new Label("允许调用工具「" + request.toolName() + "」？");
        title.getStyleClass().add("approval-title");
        Label badge = new Label(request.safe() ? "内置安全工具" : "未知工具，请仔细确认");
        badge.getStyleClass().addAll("approval-badge", request.safe() ? "safe" : "unknown");

        TextArea args = new TextArea(request.arguments() == null ? "" : request.arguments());
        args.setEditable(false);
        args.setWrapText(true);
        args.setPrefRowCount(5);
        args.getStyleClass().add("approval-args");

        Button deny = new Button("拒绝");
        deny.getStyleClass().add("ghost-button");
        Button allow = new Button("允许一次");
        allow.getStyleClass().add("primary-button");
        allow.setDefaultButton(true);

        HBox actions = new HBox(8, spacer(), deny, allow);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(10, title, badge, args, actions);
        root.getStyleClass().add("approval-root");

        Scene scene = new Scene(root, 470, 310);
        if (ownerWindow != null && ownerWindow.getScene() != null) {
            scene.getStylesheets().addAll(ownerWindow.getScene().getStylesheets());
            if (ownerWindow.getScene().getRoot().getStyleClass().contains("theme-dark")) {
                root.getStyleClass().add("theme-dark");
            }
        }
        stage.setScene(scene);
        AppIcon.apply(stage);

        allow.setOnAction(e -> {
            future.complete(ToolApprovalGate.Decision.APPROVED);
            stage.close();
        });
        deny.setOnAction(e -> {
            future.complete(ToolApprovalGate.Decision.DENIED);
            stage.close();
        });
        stage.setOnHidden(e -> future.complete(ToolApprovalGate.Decision.DENIED));

        window.set(stage);
        stage.show();
        WindowsTitleBar.apply(stage, ownerWindow);
        if (ownerWindow != null) {
            stage.setX(ownerWindow.getX() + (ownerWindow.getWidth() - stage.getWidth()) / 2);
            stage.setY(ownerWindow.getY() + (ownerWindow.getHeight() - stage.getHeight()) / 2);
        }
    }

    private void closeWindow() {
        Stage stage = window.getAndSet(null);
        if (stage == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            stage.close();
        } else {
            Platform.runLater(stage::close);
        }
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
