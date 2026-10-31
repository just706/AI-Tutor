"""Regression for the actual JDK HTTP client's wire protocol against Uvicorn."""
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time

import httpx
import pytest


def test_jdk_client_can_send_utf8_json_after_health_check(tmp_path):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK is required for Java transport regression")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    token = secrets.token_hex(24)
    environment = dict(os.environ, AGENT_MODE="demo", AGENT_SERVICE_TOKEN=token)
    source = tmp_path / "Probe.java"
    source.write_text('''
import java.net.URI;
import java.net.http.*;
public class Probe {
    public static void main(String[] args) throws Exception {
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var base="http://127.0.0.1:"+args[0];
        client.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
        var body="{\\"version\\":\\"1\\",\\"request_id\\":\\"wire-test\\",\\"attempt\\":1,\\"user_id\\":7,"
            +"\\"conversation_id\\":10,\\"document_ids\\":[11],\\"scope_version\\":\\"demo-v1\\","
            +"\\"scope_token\\":\\"local-demo\\",\\"question\\":\\"导数是什么？\\",\\"recent_user_questions\\":[]}";
        var request=HttpRequest.newBuilder(URI.create(base+"/v1/runs")).header("Authorization","Bearer "+System.getenv("AGENT_SERVICE_TOKEN"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        System.out.println(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
''', encoding="utf-8")
    with (tmp_path / "uvicorn.log").open("w") as log:
        server = subprocess.Popen([sys.executable, "-m", "uvicorn", "tutor_agent.app:build_app", "--factory",
            "--host", "127.0.0.1", "--port", str(port), "--no-access-log"], env=environment,
            cwd=Path(__file__).resolve().parents[1], stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
        try:
            with httpx.Client(trust_env=False) as client:
                for _ in range(100):
                    try:
                        if client.get(f"http://127.0.0.1:{port}/health").status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    time.sleep(0.05)
                else:
                    pytest.fail("Uvicorn startup failed")
            result = subprocess.run([java, "-Dfile.encoding=UTF-8", str(source), str(port)], env=environment,
                capture_output=True, text=True, timeout=30)
            assert result.returncode == 0, result.stderr
            assert result.stdout.strip() == "200", result.stdout
        finally:
            server.terminate()
            server.wait(timeout=5)
