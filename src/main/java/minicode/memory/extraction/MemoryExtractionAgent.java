package minicode.memory.extraction;

import minicode.agent.runtime.ModelAdapterFactory;
import minicode.config.MemoryConfig;
import minicode.context.manager.ContextManager;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.core.turn.CancellationToken;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryType;
import minicode.tools.memory.MemoryToolRegistryFactory;
import minicode.tools.memory.ReadMemoryFileTool;
import minicode.tools.memory.WriteMemoryFileTool;
import minicode.tools.registry.ToolRegistry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 内部专用的非公开 Agent 运行时，使用独立模型适配器，并且只开放两个记忆工具。
 */
public final class MemoryExtractionAgent implements MemoryExtractionRunner {
    private final ModelAdapterFactory modelAdapterFactory;
    private final MarkdownMemoryStore store;
    private final MemoryExtractionPrompt prompt;
    private final MemoryToolRegistryFactory registryFactory;

    public MemoryExtractionAgent(ModelAdapterFactory modelAdapterFactory,
                                 MarkdownMemoryStore store,
                                 MemoryExtractionPrompt prompt) {
        this(modelAdapterFactory, store, prompt, new MemoryToolRegistryFactory());
    }

    MemoryExtractionAgent(ModelAdapterFactory modelAdapterFactory,
                          MarkdownMemoryStore store,
                          MemoryExtractionPrompt prompt,
                          MemoryToolRegistryFactory registryFactory) {
        this.modelAdapterFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory");
        this.store = Objects.requireNonNull(store, "store");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.registryFactory = Objects.requireNonNull(registryFactory, "registryFactory");
    }

    @Override
    public MemoryExtractionResult run(MemoryExtractionRequest request,
                                      CancellationToken cancellationToken) {
        MemoryExtractionRequest actualRequest = Objects.requireNonNull(request, "request");
        CancellationToken actualCancellation = Objects.requireNonNull(cancellationToken, "cancellationToken");

        EnumMap<MemoryType, Integer> changes = new EnumMap<>(MemoryType.class);

        // 创建私有工具表，只包含读写记忆两个部分
        // 注册回调函数，确保文件确实改变了
        ToolRegistry registry = registryFactory.create(store, result -> {
            if (result.changed()) {
                changes.merge(result.type(), 1, Integer::sum);
            }
        });

        // 创建独立模型适配器
        ModelAdapter adapter = Objects.requireNonNull(
                modelAdapterFactory.create(registry), "memory modelAdapter");

        // 创建独立 agentLoop
        AgentLoop loop = new AgentLoop(
                adapter,
                AgentEventSink.noOp(),
                registry,
                ContextManager.noOp());
        AgentTurnResult result;
        try {
            // 构造记忆 agent 请求并调用
            result = loop.runTurn(new AgentTurnRequest(
                    "memory-" + actualRequest.turnId(),
                    actualRequest.cwd(),
                    actualRequest.sessionId(),
                    List.of(
                            new SystemMessage(prompt.systemPrompt(actualRequest)), // 分类规则、排除规则、Markdown 格式、工具纪律
                            new UserMessage(prompt.userPrompt(actualRequest)) // 对话快照 JSON
                    ),
                    MemoryConfig.EXTRACTION_MAX_STEPS, // 最大步数
                    Optional.empty(),
                    actualCancellation
            ));
        } catch (RuntimeException exception) {
            return MemoryExtractionResult.failed(message(exception));
        }

        // 如果文件有改变，判断为执行成功，返回更新
        if (!changes.isEmpty()) {
            return MemoryExtractionResult.updated(Map.copyOf(changes));
        }

        // 如果没有发生写入，解析原因
        switch (result.stopReason()) {
            // 异常结果
            case CANCELLED -> {
                return MemoryExtractionResult.cancelled("memory extraction was cancelled");
            }
            case MODEL_ERROR -> {
                return MemoryExtractionResult.failed("memory extraction model failed");
            }
            case MAX_STEPS -> {
                return MemoryExtractionResult.failed(
                        "memory extraction reached its maximum of "
                                + MemoryConfig.EXTRACTION_MAX_STEPS + " model steps");
            }
            case EMPTY_RESPONSE_FALLBACK -> {
                return MemoryExtractionResult.failed(
                        "memory extraction ended after repeated empty model responses");
            }
            case AWAIT_USER -> {
                return MemoryExtractionResult.failed(
                        "memory extraction unexpectedly requested user input");
            }
            case FINAL -> {
                // 正常最终响应需要继续根据下方的权威工具结果分类。
            }
        }
        // 没有发生写入时，代码继续检查是否调用过读取工具：
        boolean read = hasToolResult(result.messages(), ReadMemoryFileTool.NAME);
        boolean toolError = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .anyMatch(ToolResultMessage::error);
        if (toolError) {
            return MemoryExtractionResult.failed("memory tool execution failed");
        }
        // 读取过则说明已经写过记忆
        // 没读取过则说明没有记忆候选
        return read ? MemoryExtractionResult.noChange() : MemoryExtractionResult.noMemory();
    }

    private static boolean hasToolResult(List<ChatMessage> messages, String toolName) {
        return messages.stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .anyMatch(result -> toolName.equals(result.toolName()));
    }

    private static String message(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
