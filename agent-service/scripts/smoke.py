"""Start a temporary loopback demo server and verify it with real HTTP requests."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir).resolve()
    if output.exists():
        raise ValueError("Use a new output directory")
    output.mkdir(parents=True)
    service_root = Path(__file__).resolve().parents[1]
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    environment = dict(os.environ, AGENT_MODE="demo", AGENT_SERVICE_TOKEN=secrets.token_hex(24),
                       AGENT_ENABLE_REAL_MODEL="false", PYTHONUTF8="1")
    result = {"mode": "scripted_demo", "external_model_calls": 0, "checks": [], "stopped": False}
    with (output / "server.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([sys.executable, "-m", "uvicorn", "tutor_agent.app:build_app", "--factory",
            "--host", "127.0.0.1", "--port", str(port), "--workers", "1", "--no-access-log"],
            cwd=service_root, env=environment, stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
        try:
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=5, trust_env=False) as client:
                deadline = time.monotonic() + 15
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Demo server exited before readiness")
                    try:
                        health = client.get("/health")
                        if health.status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Demo server readiness timeout")
                    time.sleep(0.1)
                assert health.json()["mode"] == "demo"
                result["checks"].append("health")
                denied = client.post("/v1/runs", json={})
                assert denied.status_code == 401
                result["checks"].append("authentication")
                headers = {"Authorization": "Bearer " + environment["AGENT_SERVICE_TOKEN"]}
                scenario_data = [("answer", "导数是什么？", "answered", 3),
                    ("rewrite", "函数局部变化快慢叫什么？", "answered", 4),
                    ("read", "蓝桥编码的发明者是谁？", "answered", 4),
                    ("refuse", "这份调查的总体方差是多少？", "insufficient", 2),
                    ("clarify", "它是什么？", "clarification", 1)]
                result["scenarios"] = []
                for name, question, status, calls in scenario_data:
                    payload = {"version": "1", "request_id": f"smoke-{name}", "attempt": 1, "user_id": 7,
                        "conversation_id": 10, "document_ids": [11], "scope_version": "demo-v1", "scope_token": "local-demo",
                        "question": question, "recent_user_questions": []}
                    response = client.post("/v1/runs", json=payload, headers=headers)
                    assert response.status_code == 200, response.status_code
                    body = response.json()
                    assert body["status"] == status and body["model_calls"] == calls
                    repeated = client.post("/v1/runs", json=payload, headers=headers)
                    assert repeated.json() == body
                    changed = dict(payload, question="changed question")
                    assert client.post("/v1/runs", json=changed, headers=headers).status_code == 409
                    result["checks"].extend([name, name + "_deduplicated", name + "_conflict"])
                    result["scenarios"].append({"name": name, "result": body})
                result["passed"] = True
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            result["stopped"] = process.poll() is not None
            (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result.get("passed", False), "checks": len(result["checks"]),
        "external_model_calls": 0, "stopped": result["stopped"], "output": str(output)}, ensure_ascii=True))


if __name__ == "__main__":
    main()
