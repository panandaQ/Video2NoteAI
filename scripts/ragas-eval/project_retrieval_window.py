import json
import sys
from pathlib import Path


def overlaps(left_start, left_end, right_start, right_end):
    return max(left_start, right_start) < min(left_end, right_end)


def metric_aggregate(values, total, reasons):
    evaluated = [value for value in values if value is not None]
    unavailable = total - len(evaluated)
    if unavailable == 0:
        reason = None
    elif not evaluated:
        reason = next((reason for reason in reasons if reason), "Metric is unavailable")
    else:
        reason = "Partially unavailable; inspect per-turn unavailableReasons"
    return {
        "value": sum(evaluated) / len(evaluated) if evaluated else None,
        "evaluatedCount": len(evaluated),
        "unavailableCount": unavailable,
        "unavailableReason": reason,
    }


def summarize(turns):
    names = ["rewriteAccuracy", "hitAt5", "recallAt5", "mrr",
             "answerKeyPointRecall", "citationPrecision",
             "timestampOverlapRate", "unsupportedClaimRate"]
    total = len(turns)
    completed = sum(turn.get("status") == "COMPLETED" for turn in turns)
    mode_turns = [turn for turn in turns if turn.get("actualAnswerMode") is not None]
    mode_matches = sum(
        (turn.get("actualAnswerMode") in ("VIDEO_GROUNDED", "HYBRID"))
        == bool(turn.get("expectedAnswerable"))
        for turn in mode_turns
    )
    raw = sum(turn.get("rawCitationCount", 0) for turn in turns)
    fabricated = sum(turn.get("fabricatedCitationCount", 0) for turn in turns)
    grounded = [
        turn["metrics"]["unsupportedClaimRate"]
        for turn in turns
        if turn.get("actualAnswerMode") in ("VIDEO_GROUNDED", "HYBRID")
        and turn.get("metrics", {}).get("unsupportedClaimRate") is not None
    ]
    metrics = {}
    unavailable_metrics = {}
    for name in names:
        values = [turn.get("metrics", {}).get(name) for turn in turns]
        reasons = [turn.get("metrics", {}).get("unavailableReasons", {}).get(name) for turn in turns]
        aggregate = metric_aggregate(values, total, reasons)
        metrics[name] = aggregate
        if aggregate["unavailableReason"] is not None:
            unavailable_metrics[name] = aggregate["unavailableReason"]
    durations = sorted(
        turn["systemMetrics"]["durationMs"]
        for turn in turns
        if turn.get("systemMetrics", {}).get("durationMs") is not None
    )

    def percentile(p):
        if not durations:
            return None
        index = max(0, int(p * len(durations) + 0.999999) - 1)
        return durations[min(index, len(durations) - 1)]

    return {
        "totalTurns": total,
        "succeededTurns": completed,
        "failedTurns": total - completed,
        "answerModeAccuracy": mode_matches / len(mode_turns) if mode_turns else None,
        "answerModeEvaluatedCount": len(mode_turns),
        "falseVideoAttributionRate": sum(grounded) / len(grounded) if grounded else None,
        "fabricatedCitationRate": fabricated / raw if raw else None,
        "metrics": metrics,
        "unavailableMetrics": unavailable_metrics,
        "durationP50Ms": percentile(0.50),
        "durationP95Ms": percentile(0.95),
        "modelCalls": None,
        "estimatedTokens": None,
        "estimatedCost": None,
    }


def project_turn(result, gold, limit):
    hits = result.get("retrievalTop5", [])[:limit]
    evidence = gold.get("goldEvidence", [])
    metrics = result.get("metrics", {})
    reasons = metrics.setdefault("unavailableReasons", {})
    if not evidence:
        for name in ("hitAt5", "recallAt5", "mrr"):
            metrics[name] = None
            reasons[name] = "Gold turn has no evidence intervals"
    else:
        relevant_ranks = [
            index + 1
            for index, hit in enumerate(hits)
            if any(overlaps(hit["startMs"], hit["endMs"], item["startMs"], item["endMs"])
                   for item in evidence)
        ]
        first = min(relevant_ranks) if relevant_ranks else 0
        recalled = sum(
            any(overlaps(hit["startMs"], hit["endMs"], item["startMs"], item["endMs"])
                for hit in hits)
            for item in evidence
        )
        metrics["hitAt5"] = 1.0 if first else 0.0
        metrics["recallAt5"] = recalled / len(evidence)
        metrics["mrr"] = 1.0 / first if first else 0.0
        reasons.pop("hitAt5", None)
        reasons.pop("recallAt5", None)
        reasons.pop("mrr", None)
    result["retrievalTop5"] = hits
    return result


def main(source, target, dataset_path, limit):
    dataset = json.loads(Path(dataset_path).read_text(encoding="utf-8"))
    gold = {
        (case["conversationCaseId"], turn["turnNo"]): turn
        for case in dataset["cases"] for turn in case["turns"]
    }
    variant_turns = {}
    case_turns = {}
    with Path(source).open(encoding="utf-8") as reader, Path(target).open("w", encoding="utf-8") as writer:
        for line in reader:
            event = json.loads(line)
            event_type = event.get("type")
            variant = event.get("variant")
            if event_type == "run_started":
                event["metadata"]["runId"] = event["metadata"].get("runId", "") + "-top5"
                event["metadata"].setdefault("retrievalParameters", {})["evaluationTopK"] = limit
                for key in ("hitAt5", "recallAt5", "mrr"):
                    event["metricDefinitions"][key] = event["metricDefinitions"][key].replace("Top-20", "Top-5")
            elif event_type == "turn_completed":
                case_id = event["conversationCaseId"]
                turn = event["result"]
                transformed = project_turn(turn, gold[(case_id, turn["turnNo"])], limit)
                event["result"] = transformed
                variant_turns.setdefault(variant, []).append(transformed)
                case_turns.setdefault((variant, case_id), []).append(transformed)
            elif event_type == "case_completed":
                event["summary"] = summarize(case_turns[(variant, event["conversationCaseId"])])
            elif event_type == "variant_completed":
                event["summary"] = summarize(variant_turns[variant])
            elif event_type == "run_completed":
                event["metadata"]["runId"] = event["metadata"].get("runId", "") + "-top5"
                event["metadata"].setdefault("retrievalParameters", {})["evaluationTopK"] = limit
                event["summaries"] = {key: summarize(value) for key, value in variant_turns.items()}
            writer.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]))
