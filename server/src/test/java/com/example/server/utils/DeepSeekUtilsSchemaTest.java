package com.example.server.utils;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.ChunkCleanResult;
import com.example.server.dto.ModeClassification;
import com.example.server.dto.knowledge.GroundedAnswerResult;
import com.example.server.dto.knowledge.QueryPlan;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出契约（D-111）：每个走结构化输出的 DTO 都必须能生成 OpenAI 接受的 schema。
 *
 * <p>为什么需要这条测试：schema 由 DTO 反射生成，DTO 改字段不会在编译期暴露问题，
 * 要等真实调用被服务商 400 拒掉才发现。这里把「根元素必须是 object」这条服务商硬约束
 * 提前到单测——{@code OpenAiUtils.toOpenAiResponseFormat} 对非 object/raw 根会直接抛异常。
 */
class DeepSeekUtilsSchemaTest {

    /** 所有经 {@code structuredChat} 走结构化输出的目标类型。 */
    private static final List<Class<?>> STRUCTURED_TYPES = List.of(
            AgentState.AgentPlan.class,
            AgentState.CriticResult.class,
            AnalysisResult.class,
            ModeClassification.class,
            QueryPlan.class,
            GroundedAnswerResult.class,
            ChunkCleanResult.ChunkCleanBatch.class);

    @Test
    void everyStructuredDtoYieldsObjectRootSchema() {
        for (Class<?> type : STRUCTURED_TYPES) {
            ResponseFormat format = DeepSeekUtils.responseFormatFor(type);
            assertNotNull(format.jsonSchema(), type.getSimpleName() + ": 必须产出 schema");
            assertInstanceOf(JsonObjectSchema.class, format.jsonSchema().rootElement(),
                    type.getSimpleName() + ": 根元素必须是 object（OpenAI 只接受 object/raw 根）");
        }
    }

    /** 字段级回归：schema 必须真的包含 DTO 的字段，而不是退化成空对象。 */
    @Test
    void queryPlanSchemaExposesQueryAndCorrectionFields() {
        String schema = JsonSchemaElementUtils
                .toMap(DeepSeekUtils.responseFormatFor(QueryPlan.class).jsonSchema().rootElement(), true)
                .toString();

        for (String field : List.of("standaloneQuery", "semanticQuery", "keywords", "visualKeywords",
                "originalTerms", "correctedTerms", "corrections")) {
            assertTrue(schema.contains(field), "schema 缺少字段 " + field + "：" + schema);
        }
    }

    /** 来源模式与分栏是回答链路的核心契约，schema 必须覆盖这些字段。 */
    @Test
    void groundedAnswerSchemaExposesSourceModeFields() {
        String schema = JsonSchemaElementUtils
                .toMap(DeepSeekUtils.responseFormatFor(GroundedAnswerResult.class)
                        .jsonSchema().rootElement(), true)
                .toString();

        for (String field : List.of("answerMode", "videoAnswer", "modelSupplement", "citedEvidenceIds",
                "sourceNotice")) {
            assertTrue(schema.contains(field), "schema 缺少字段 " + field + "：" + schema);
        }
    }
}
