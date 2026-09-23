package com.example.server.evaluation.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.Comparator;

/** 计算可写回数据集自身的稳定哈希：排除 datasetSha256 字段并规范化对象键顺序。 */
public final class EvaluationDatasetHasher {

    private EvaluationDatasetHasher() { }

    public static String sha256(byte[] datasetBytes, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(datasetBytes);
            if (!(root instanceof ObjectNode rootObject)) {
                throw new IllegalArgumentException("evaluation dataset root must be an object");
            }
            JsonNode provenance = rootObject.path("provenance");
            if (provenance instanceof ObjectNode provenanceObject) {
                provenanceObject.remove("datasetSha256");
            }
            byte[] canonical = objectMapper.writeValueAsBytes(sorted(rootObject, objectMapper));
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid evaluation dataset JSON", e);
        }
    }

    private static JsonNode sorted(JsonNode node, ObjectMapper objectMapper) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> result.set(entry.getKey(), sorted(entry.getValue(), objectMapper)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(sorted(child, objectMapper)));
            return result;
        }
        return node.deepCopy();
    }
}
