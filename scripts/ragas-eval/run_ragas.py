#!/usr/bin/env python
"""RAGAs 离线旁路评测：读本项目评测报告，补生成层的「相关性」维度。

为什么需要它：项目现有的生成层指标（unsupportedClaimRate / falseVideoAttributionRate /
fabricatedCitationRate / citationPrecision / timestampOverlapRate）全部是**忠实性**维度
——"有没有证据支撑"。而"答案切不切题"是正交的另一个维度：一个答非所问但忠实复述了
证据的回答，在现有指标下能拿满分。RAGAs 的 Answer Relevancy 正好补这一维。

它同时跑 Faithfulness 做**交叉验证**：本项目 ClaimJudge 实现的算法与 RAGAs 同源
（拆原子声明 → 逐条判能否由检索上下文推断 → 支持数/总数），两边结果应当接近。

**不接进生产链路**：本脚本只读评测报告与本地 .env，不触碰问答请求路径。

用法：
    scripts/ragas-eval/.venv/Scripts/python.exe scripts/ragas-eval/run_ragas.py \
        [--report .tmp/rag/evaluation-report.json] [--out .tmp/rag/ragas-report.json]

判分模型凭据优先级：RAGAS_JUDGE_* > 项目的 CHAT_* / DEEPSEEK_API_KEY。
"""

import argparse
import asyncio
import time
from concurrent.futures import ThreadPoolExecutor, wait as futures_wait
import json
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_JUDGE_MODEL = "qwen3.8-27b"
DEFAULT_EMBEDDING_MODEL = "qwen3.7-text-embedding"


def load_env(path: Path) -> dict:
    """读 bash 风格 .env：只取 KEY=VALUE，剥掉成对引号。"""
    env = {}
    if not path.exists():
        return env
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        env[key] = value
    return env


def resolve_judge_config(env: dict) -> tuple:
    """判分模型端点：显式 RAGAS_JUDGE_* 优先，回落到项目的 CHAT_*。"""
    base_url = env.get("RAGAS_JUDGE_BASE_URL") or env.get("CHAT_BASE_URL") or env.get("SILICONFLOW_BASE_URL")
    api_key = (env.get("RAGAS_JUDGE_API_KEY") or env.get("CHAT_API_KEY")
               or env.get("DEEPSEEK_API_KEY") or env.get("SILICONFLOW_API_KEY"))
    return base_url, api_key


def check_isolation(generator: str, production: str, judge: str) -> None:
    """三方隔离（与项目 JudgeModelConfig.validateIsolation 同一原则）：无法证明隔离就拒绝运行。

    引框架不能丢掉这条——否则 RAGAs 用它自己配的模型判分，可能正好是生产回答模型，
    评测就变成"自己判自己"。
    """
    if not generator or not production:
        raise SystemExit("报告 metadata 缺少 generatorModel / model，无法证明隔离")
    if judge.lower() == generator.lower():
        raise SystemExit(f"JUDGE_EQUALS_GENERATOR_MODEL: {judge}")
    if judge.lower() == production.lower():
        raise SystemExit(f"JUDGE_EQUALS_PRODUCTION_MODEL: {judge}")
    if generator.lower() == production.lower():
        raise SystemExit(f"GENERATOR_EQUALS_PRODUCTION_MODEL: {generator}")


def build_samples(report: dict) -> list:
    """评测报告 → RAGAs 样本。只取成功执行且问答俱全的轮次。"""
    samples = []
    for case in report.get("variants", [{}])[0].get("cases", []):
        for turn in case.get("turns", []):
            if turn.get("status") != "COMPLETED":
                continue
            question = (turn.get("question") or "").strip()
            answer = (turn.get("answer") or "").strip()
            if not question or not answer:
                continue
            contexts = [
                (hit.get("snippet") or "").strip()
                for hit in (turn.get("retrievalTop5") or [])
                if (hit.get("snippet") or "").strip()
            ]
            samples.append({
                "conversationCaseId": case.get("conversationCaseId"),
                "turnNo": turn.get("turnNo"),
                "category": turn.get("category"),
                "expectedAnswerable": turn.get("expectedAnswerable"),
                "actualAnswerMode": turn.get("actualAnswerMode"),
                "user_input": question,
                "response": answer,
                "retrieved_contexts": contexts,
            })
    return samples


def base_row(sample: dict) -> dict:
    return {
        "conversationCaseId": sample["conversationCaseId"],
        "turnNo": sample["turnNo"],
        "category": sample["category"],
        "expectedAnswerable": sample["expectedAnswerable"],
        "actualAnswerMode": sample.get("actualAnswerMode"),
        "contextCount": len(sample["retrieved_contexts"]),
        "faithfulness": None,
        "answerRelevancy": None,
        "errors": [],
    }


def score_faithfulness(sample: dict, scorer) -> dict:
    """faithfulness（同步）：只在「声称有视频依据」的轮次适用。

    MODEL_KNOWLEDGE 的定义就是「答案来自模型知识」，它对视频证据不忠实是**设计如此**——
    对它算 faithfulness 是范畴错误，会凭空拉低均值。口径与 Java 侧 EvaluationMetrics
    对 unsupportedClaimRate 的排除一致（那里报 unavailable，不是 0）。
    """
    out = {"value": None, "error": None}
    if sample.get("actualAnswerMode") == "MODEL_KNOWLEDGE":
        out["error"] = "faithfulness: 实际为 MODEL_KNOWLEDGE，不适用（本轮不声称视频依据）"
        return out
    if not sample["retrieved_contexts"]:
        out["error"] = "faithfulness: 无检索上下文，不适用"
        return out
    try:
        result = scorer.score(
            user_input=sample["user_input"],
            response=sample["response"],
            retrieved_contexts=sample["retrieved_contexts"],
        )
        out["value"] = round(float(result.value), 4)
    except Exception as exc:  # noqa: BLE001 — 逐轮记录，不中断
        out["error"] = f"faithfulness: {type(exc).__name__}: {exc}"
    return out


def score_relevancy(sample: dict, scorer) -> dict:
    """answer_relevancy（同步）：与证据无关，全轮次适用。"""
    out = {"value": None, "error": None}
    try:
        result = scorer.score(
            user_input=sample["user_input"],
            response=sample["response"],
        )
        out["value"] = round(float(result.value), 4)
    except Exception as exc:  # noqa: BLE001
        out["error"] = f"answer_relevancy: {type(exc).__name__}: {exc}"
    return out


def mean_of(rows: list, key: str):
    values = [r[key] for r in rows if r.get(key) is not None]
    return (sum(values) / len(values), len(values)) if values else (None, 0)


async def run(args) -> int:
    env = load_env(REPO_ROOT / ".env")
    base_url, api_key = resolve_judge_config(env)
    if not api_key or not base_url:
        print("缺少判分模型凭据（RAGAS_JUDGE_* 或 CHAT_* / DEEPSEEK_API_KEY）", file=sys.stderr)
        return 2

    report_path = Path(args.report) if Path(args.report).is_absolute() else REPO_ROOT / args.report
    if not report_path.exists():
        print(f"评测报告不存在：{report_path}", file=sys.stderr)
        return 2
    report = json.loads(report_path.read_text(encoding="utf-8"))

    meta = report.get("metadata", {})
    generator = meta.get("generatorModel", "")
    production = meta.get("model", "") or meta.get("productionAnswerModel", "")
    check_isolation(generator, production, args.judge_model)

    samples = build_samples(report)
    if not samples:
        print("报告里没有可评测的轮次", file=sys.stderr)
        return 2
    if args.limit > 0:
        samples = samples[:args.limit]
        print(f"[limit] 只跑前 {len(samples)} 轮")

    print(f"判分端点 : {base_url}")
    print(f"判分模型 : {args.judge_model}")
    print(f"embedding: {args.embedding_model}")
    print(f"隔离校验 : generator={generator!r} / production={production!r} / judge={args.judge_model!r}  → 通过")
    print(f"样本     : {len(samples)} 轮（{report_path.name}）")

    from openai import AsyncOpenAI
    from ragas.embeddings import OpenAIEmbeddings
    from ragas.llms import llm_factory
    from ragas.metrics.collections import AnswerRelevancy, Faithfulness

    client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=180)
    judge_llm = llm_factory(args.judge_model, client=client)
    judge_embeddings = OpenAIEmbeddings(client=client, model=args.embedding_model)

    faithfulness = Faithfulness(llm=judge_llm)
    answer_relevancy = AnswerRelevancy(llm=judge_llm, embeddings=judge_embeddings)

    out_path = Path(args.out) if Path(args.out).is_absolute() else REPO_ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    semaphore = asyncio.Semaphore(args.concurrency)

    def write_report(rows_out: list) -> None:
        """增量落盘：任何时刻中断，已完成的部分都留得下来。"""
        faith_mean, faith_n = mean_of(rows_out, "faithfulness")
        relev_mean, relev_n = mean_of(rows_out, "answerRelevancy")
        out_path.write_text(json.dumps({
            "schemaVersion": "ragas-sidecar-v1",
            "judgeBaseUrl": base_url,
            "judgeModel": args.judge_model,
            "embeddingModel": args.embedding_model,
            "generatorModel": generator,
            "productionAnswerModel": production,
            "sampleCount": len(samples),
            "completedCount": len(rows_out),
            "aggregate": {
                "faithfulness": faith_mean,
                "faithfulnessEvaluated": faith_n,
                "answerRelevancy": relev_mean,
                "answerRelevancyEvaluated": relev_n,
            },
            "turns": sorted(rows_out, key=lambda r: (r["conversationCaseId"], r["turnNo"])),
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    # 线程池 + 墙钟超时（而不是 asyncio）：实测 ragas 的 ascore 内部有同步阻塞调用，
    # 会冻住整个事件循环——并发退化为串行，且 asyncio.wait_for 的超时点不着，
    # 一轮卡住整批就永远不结束。线程池下卡住的是单个线程，其余照常推进；
    # 超时按墙钟判定并放弃该线程，评测不因此停摆。

    rows = [base_row(s) for s in samples]
    futures = {}
    started_at = {}   # (index, kind) -> 该任务【真正开始执行】的时刻

    def timed(index, kind, fn, sample, scorer):
        started_at[(index, kind)] = time.monotonic()
        return fn(sample, scorer)

    # shutdown(wait=False)：已判定超时并放弃的线程不再等待——
    # 否则 `with` 退出时会阻塞到那些卡住的线程自己结束，评测还是结束不了。
    pool = ThreadPoolExecutor(max_workers=args.concurrency)
    try:
        for index, sample in enumerate(samples):
            for kind, fn, scorer in (("faithfulness", score_faithfulness, faithfulness),
                                     ("answerRelevancy", score_relevancy, answer_relevancy)):
                fut = pool.submit(timed, index, kind, fn, sample, scorer)
                futures[fut] = (index, kind, time.monotonic())

        pending = set(futures)
        completed = 0
        while pending:
            done, pending = futures_wait(pending, timeout=5)
            for fut in done:
                index, kind, _ = futures[fut]
                try:
                    res = fut.result()
                    rows[index][kind] = res["value"]
                    if res["error"]:
                        rows[index]["errors"].append(res["error"])
                except Exception as exc:  # noqa: BLE001
                    rows[index]["errors"].append(f"{kind}: {type(exc).__name__}: {exc}")
                completed += 1
                mark = f"  ⚠ {rows[index]['errors'][-1][:46]}" if rows[index]["errors"] else ""
                print(f"  [{completed:>3}/{len(futures)}] "
                      f"{samples[index]['conversationCaseId']} t{samples[index]['turnNo']} "
                      f"{kind}={rows[index][kind]}{mark}", flush=True)
                if completed % 20 == 0 or completed == len(futures):
                    write_report(rows)
            # 超时按【任务真正开始执行】的墙钟算，而不是提交时刻——
            # 串行执行时后面的任务会在队列里排很久，按提交时刻算会把它们全部误判为超时。
            now = time.monotonic()
            for fut in list(pending):
                index, kind, submitted = futures[fut]
                began = started_at.get((index, kind))
                if began is None:
                    continue          # 还没轮到它执行，不给超时
                if now - began > args.timeout:
                    rows[index]["errors"].append(
                        f"{kind}: 超时 {args.timeout:.0f}s（线程已放弃，不阻塞其余轮次）")
                    pending.discard(fut)
                    completed += 1
                    print(f"  [{completed:>3}/{len(futures)}] "
                          f"{samples[index]['conversationCaseId']} t{samples[index]['turnNo']} "
                          f"{kind}=TIMEOUT", flush=True)
    finally:
        pool.shutdown(wait=False)

    faith_mean, faith_n = mean_of(rows, "faithfulness")
    relev_mean, relev_n = mean_of(rows, "answerRelevancy")

    print("\n=== RAGAs 结果 ===")
    print(f"  faithfulness      {faith_mean if faith_mean is None else round(faith_mean, 4)}   (n={faith_n})")
    print(f"  answer_relevancy  {relev_mean if relev_mean is None else round(relev_mean, 4)}   (n={relev_n})")
    print(f"\n明细已写入 {out_path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="RAGAs 离线旁路评测")
    parser.add_argument("--report", default=".tmp/rag/evaluation-report.json")
    parser.add_argument("--out", default=".tmp/rag/ragas-report.json")
    parser.add_argument("--judge-model",
                        default=os.environ.get("RAGAS_JUDGE_MODEL", DEFAULT_JUDGE_MODEL))
    parser.add_argument("--embedding-model",
                        default=os.environ.get("RAGAS_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL))
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 轮，用于小批验证（0=全量）")
    parser.add_argument("--timeout", type=float, default=120, help="单轮单指标超时秒数；超时按该轮失败记录，不拖住整体")
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
