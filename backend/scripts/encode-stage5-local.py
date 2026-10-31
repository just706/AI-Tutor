"""Encode only local synthetic retrieval input with a pinned, offline BGE model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import sys
import time

# These are set before importing libraries; network resolution and remote Python are disabled.
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"


def checksum(filename):
    return hashlib.sha256(Path(filename).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-file", required=True)
    parser.add_argument("--config-file", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--output-file", required=True)
    args = parser.parse_args()
    destination = Path(args.output_file)
    if destination.exists():
        raise ValueError("Existing embeddings must not be overwritten")
    config = json.loads(Path(args.config_file).read_text(encoding="utf-8"))
    request = json.loads(Path(args.input_file).read_text(encoding="utf-8"))
    model_dir = Path(args.model_dir).resolve()
    manifest_file = model_dir / "manifest.json"
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    if (manifest["modelId"], manifest["revision"]) != (config["modelId"], config["revision"]):
        raise ValueError("Model manifest does not match the frozen configuration")
    required = {"README.md", "config.json", "tokenizer.json", "tokenizer_config.json",
                "special_tokens_map.json", "vocab.txt", "model.safetensors"}
    if set(manifest["files"]) != required:
        raise ValueError("Unexpected model files")
    for name, record in manifest["files"].items():
        if checksum(model_dir / name) != record["sha256"]:
            raise ValueError(f"Model checksum mismatch: {name}")
    if config["pooling"] != "cls" or config["normalizeEmbeddings"] is not True:
        raise ValueError("This probe only implements normalized CLS pooling")
    import torch
    import transformers
    from transformers import AutoModel, AutoTokenizer

    torch.set_num_threads(config["cpuThreads"])
    torch.manual_seed(0)
    torch.use_deterministic_algorithms(True)
    started = time.perf_counter()
    tokenizer = AutoTokenizer.from_pretrained(str(model_dir), local_files_only=True, trust_remote_code=False)
    model = AutoModel.from_pretrained(str(model_dir), local_files_only=True, trust_remote_code=False,
                                     use_safetensors=True, attn_implementation="eager")
    model.eval()
    model_load_ms = (time.perf_counter() - started) * 1000
    forward_passes = 0

    def encode(texts):
        nonlocal forward_passes
        tokens = tokenizer(texts, padding=True, truncation=False, return_tensors="pt")
        if tokens["input_ids"].shape[1] > config["maxTokens"]:
            raise ValueError("Input exceeds the frozen token limit; truncation would change the corpus")
        with torch.inference_mode():
            hidden = model(**tokens).last_hidden_state[:, 0]
            vectors = torch.nn.functional.normalize(hidden, p=2, dim=1)
        forward_passes += 1
        if not bool(torch.isfinite(vectors).all()):
            raise ValueError("Non-finite model embeddings")
        return vectors.tolist(), tokens["attention_mask"].sum(dim=1).tolist()

    for _ in range(2):
        encode([config["queryPrefix"] + "本地检索预热。"])
    documents = []
    index_started = time.perf_counter()
    for offset in range(0, len(request["documents"]), config["batchSize"]):
        batch = request["documents"][offset:offset + config["batchSize"]]
        vectors, token_counts = encode([item["text"] for item in batch])
        documents.extend({"id": item["id"], "vector": vector, "tokens": tokens}
                         for item, vector, tokens in zip(batch, vectors, token_counts))
    index_ms = (time.perf_counter() - index_started) * 1000
    queries = []
    for item in request["queries"]:
        samples = []
        reference = None
        for _ in range(3):
            started = time.perf_counter()
            vectors, token_counts = encode([config["queryPrefix"] + item["text"]])
            samples.append((time.perf_counter() - started) * 1000)
            vector = vectors[0]
            if reference is not None and any(abs(a - b) > 1e-6 for a, b in zip(reference, vector)):
                raise ValueError("Non-deterministic query embeddings")
            reference = vector
        queries.append({"id": item["id"], "text": item["text"], "vector": reference,
                        "tokens": token_counts[0], "elapsedMs": statistics.median(samples), "timingSamplesMs": samples})
    result = {"documents": documents, "queries": queries, "provenance": {
        "modelId": config["modelId"], "revision": config["revision"], "modelManifestSha256": checksum(manifest_file),
        "modelFiles": manifest["files"], "inputSha256": checksum(args.input_file), "configSha256": checksum(args.config_file),
        "encoderSha256": checksum(__file__), "python": sys.version.split()[0], "torch": torch.__version__,
        "transformers": transformers.__version__, "device": "cpu", "dtype": str(model.dtype),
        "cpuThreads": torch.get_num_threads(), "remoteCode": False, "localFilesOnly": True,
        "externalModelCalls": 0, "localForwardPasses": forward_passes, "modelLoadMs": model_load_ms,
        "documentEncodingMs": index_ms, "queryMeasurements": 3, "warmupPasses": 2}}
    with destination.open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")
    print(json.dumps({"documents": len(documents), "queries": len(queries), "localForwardPasses": forward_passes,
                      "externalModelCalls": 0, "modelLoadMs": model_load_ms, "documentEncodingMs": index_ms}))


if __name__ == "__main__":
    main()
