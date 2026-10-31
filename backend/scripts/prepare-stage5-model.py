"""Download a pinned public encoder into a new directory; no project data is sent."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

MODEL_ID = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
FILES = ["README.md", "config.json", "tokenizer.json", "tokenizer_config.json",
         "special_tokens_map.json", "vocab.txt", "model.safetensors"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir).resolve()
    if output.exists():
        raise ValueError("Choose a new directory; existing model files are never overwritten")
    metadata_url = f"https://huggingface.co/api/models/{MODEL_ID}/revision/{REVISION}?blobs=true"
    with urllib.request.urlopen(metadata_url, timeout=30) as response:
        metadata = json.load(response)
    if metadata["sha"] != REVISION:
        raise ValueError("Model revision mismatch")
    remote_files = {item["rfilename"]: item for item in metadata["siblings"]}
    manifest = {"modelId": MODEL_ID, "revision": REVISION, "files": {}}
    output.mkdir(parents=True, exist_ok=False)
    total_bytes = 0
    for name in FILES:
        metadata_file = remote_files[name]
        url = f"https://huggingface.co/{MODEL_ID}/resolve/{REVISION}/{urllib.parse.quote(name)}"
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(url, timeout=45) as response, (output / name).open("xb") as destination:
            while block := response.read(1024 * 1024):
                total_bytes += len(block)
                size += len(block)
                if total_bytes > 150 * 1024 * 1024:
                    raise ValueError("Model download exceeded 150 MiB limit")
                digest.update(block)
                destination.write(block)
        if size != metadata_file["size"]:
            raise ValueError(f"Download size mismatch: {name}")
        checksum = digest.hexdigest()
        expected = metadata_file.get("lfs", {}).get("sha256")
        if expected and checksum != expected:
            raise ValueError(f"Model checksum mismatch: {name}")
        manifest["files"][name] = {"bytes": size, "sha256": checksum, "upstreamLfsSha256": expected}
        print(f"Downloaded {name}: {size} bytes", flush=True)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"modelId": MODEL_ID, "revision": REVISION, "totalBytes": total_bytes,
                      "output": str(output)}, ensure_ascii=True))


if __name__ == "__main__":
    main()
