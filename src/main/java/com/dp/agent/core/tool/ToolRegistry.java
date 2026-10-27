package com.dp.agent.core.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反射扫描 @Tool 方法并注册为「带授权与审计的执行器」。
 * 保留「新增 @Tool 自动生效」的约定；未知工具默认需要确认。
 */
public final class ToolRegistry {

    /** 已知只读/无副作用的工具，可跳过确认。新增工具默认不在此列。 */
    private static final Set<String> SAFE_TOOLS = Set.of(
            "calculator", "currentTime", "formatData", "minifyJson");

    public record Registration(String name, ToolSpecification specification,
                               ToolExecutor delegate, boolean safe) {
    }

    private final Map<String, Registration> registrations = new LinkedHashMap<>();

    public static ToolRegistry scan(Object... toolObjects) {
        ToolRegistry registry = new ToolRegistry();
        for (Object object : toolObjects) {
            registry.register(object);
        }
        return registry;
    }

    public ToolRegistry register(Object toolObject) {
        for (Method method : toolObject.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            try {
                String name = ToolSpecifications.toolNameFrom(method);
                if (registrations.containsKey(name)) {
                    System.err.println("[dp-agent] 重复的工具名已忽略: " + name);
                    continue;
                }
                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                ToolExecutor delegate = new DefaultToolExecutor(toolObject, method);
                registrations.put(name, new Registration(name, specification, delegate,
                        SAFE_TOOLS.contains(name)));
            } catch (RuntimeException e) {
                System.err.println("[dp-agent] 跳过无法注册的工具方法 " + method.getName()
                        + ": " + e.getMessage());
            }
        }
        return this;
    }

    public int size() {
        return registrations.size();
    }

    public Set<String> names() {
        return Set.copyOf(registrations.keySet());
    }

    public boolean isSafe(String toolName) {
        Registration registration = registrations.get(toolName);
        return registration != null && registration.safe();
    }

    public List<ToolSpecification> specifications() {
        List<ToolSpecification> specifications = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            specifications.add(registration.specification());
        }
        return specifications;
    }

    public Map<ToolSpecification, ToolExecutor> guardedExecutors(ToolRuntime runtime) {
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (Registration registration : registrations.values()) {
            executors.put(registration.specification(), new GuardedToolExecutor(
                    registration.name(), registration.delegate(), registration.safe(), runtime));
        }
        return executors;
    }
}
