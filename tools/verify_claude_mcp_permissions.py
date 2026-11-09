#!/usr/bin/env python3
"""Exercise actual Claude MCP permissions using only local deterministic servers."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import uuid


def mcp_main(log, tools):
    for line in sys.stdin:
        message = json.loads(line)
        method = message.get("method")
        if "id" not in message:
            continue
        if method == "initialize":
            result = {"protocolVersion": message["params"]["protocolVersion"],
                      "capabilities": {"tools": {}}, "serverInfo": {"name": "permission-canary", "version": "1"}}
        elif method == "tools/list":
            result = {"tools": [{"name": tool, "description": "Local permission canary; records its own name only.",
                                  "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}}
                                 for tool in tools]}
        elif method == "tools/call":
            name = message["params"]["name"]
            with open(log, "a") as output:
                output.write(json.dumps({"tool": name, "time_ns": time.time_ns()}) + "\n")
            result = {"content": [{"type": "text", "text": "benign local canary called"}]}
        else:
            result = {}
        print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}), flush=True)


class Api(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        self.server.requests.append({"path": self.path, "tools": [t.get("name") for t in body.get("tools", [])]})
        if "count_tokens" in self.path:
            return self.send_json({"input_tokens": 50})
        if not self.path.startswith("/v1/messages"):
            return self.send_json({})
        tool_result = any(isinstance(message.get("content"), list) and any(
            block.get("type") == "tool_result" for block in message["content"])
            for message in body.get("messages", []))
        if tool_result:
            content = [{"type": "text", "text": "Permission canary finished."}]
            stop_reason = "end_turn"
        else:
            content = [{"type": "tool_use", "id": "toolu_canary", "name": self.server.tool, "input": {}}]
            stop_reason = "tool_use"
        response = {"id": "msg_canary", "type": "message", "role": "assistant",
                    "model": body.get("model", "claude-sonnet-4-5"), "content": content,
                    "stop_reason": stop_reason, "stop_sequence": None,
                    "usage": {"input_tokens": 50, "output_tokens": 10}}
        if not body.get("stream"):
            return self.send_json(response)
        events = [("message_start", {"type": "message_start", "message": dict(response, content=[], stop_reason=None)})]
        for index, block in enumerate(content):
            events.append(("content_block_start", {"type": "content_block_start", "index": index,
                                                   "content_block": block}))
            events.append(("content_block_stop", {"type": "content_block_stop", "index": index}))
        events.extend([
            ("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop_reason, "stop_sequence": None},
                               "usage": {"output_tokens": 10}}),
            ("message_stop", {"type": "message_stop"})])
        data = "".join(f"event: {event}\ndata: {json.dumps(value)}\n\n" for event, value in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_json(self, value):
        data = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_CONNECT(self):
        self.server.requests.append({"blocked_proxy_connect": self.path})
        self.send_error(502, "Only loopback canary traffic is permitted")


def run_case(cli, output, name, settings, server_name, target, all_tools, expect_prompt):
    case = output / name
    case.mkdir(parents=True, exist_ok=False)
    config = case / "config"
    config.mkdir()
    workspace = case / "workspace"
    workspace.mkdir()
    settings_file = case / "settings.json"
    settings_file.write_text(json.dumps(settings, indent=2) + "\n")
    mcp_log = case / "mcp-calls.jsonl"
    mcp_config = case / "mcp.json"
    mcp_config.write_text(json.dumps({"mcpServers": {server_name: {
        "command": sys.executable, "args": [str(Path(__file__).resolve()), "mcp", str(mcp_log), *all_tools]}}}))
    api = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Api)
    api.requests = []
    api.tool = f"mcp__{server_name}__{target}"
    thread = threading.Thread(target=api.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{api.server_port}"
    env = {key: value for key, value in os.environ.items() if key in ["PATH", "LANG", "LC_ALL", "TZ", "TERM"]}
    env.update({"CLAUDE_CONFIG_DIR": str(config), "ANTHROPIC_API_KEY": "local-canary-only",
                "ANTHROPIC_BASE_URL": url, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                "DISABLE_TELEMETRY": "1", "DISABLE_ERROR_REPORTING": "1", "DISABLE_AUTOUPDATER": "1",
                "ENABLE_TOOL_SEARCH": "false", "HTTP_PROXY": url, "HTTPS_PROXY": url,
                "NO_PROXY": "localhost,127.0.0.1,::1"})
    cmd = [cli, "--bare", "--print", "--verbose", "--setting-sources", "", "--strict-mcp-config",
           "--mcp-config", str(mcp_config), "--settings", str(settings_file), "--permission-mode", "manual",
           "--permission-prompts", "host", "--permission-prompt-tool", "stdio", "--tools", "",
           "--input-format", "stream-json", "--output-format", "stream-json", "--no-session-persistence",
           "--model", "claude-sonnet-4-5", "--max-turns", "3"]
    events, prompts = [], []
    proc = None
    result = None
    try:
        with (case / "stderr.log").open("w") as stderr:
            proc = subprocess.Popen(cmd, cwd=workspace, env=env, stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=stderr, text=True)
            messages = queue.Queue()
            def reader():
                for line in proc.stdout:
                    messages.put(line)
                messages.put(None)
            threading.Thread(target=reader, daemon=True).start()
            def send(message):
                proc.stdin.write(json.dumps(message) + "\n")
                proc.stdin.flush()
            send({"type": "control_request", "request_id": "initialize-canary",
                  "request": {"subtype": "initialize", "hooks": None}})
            deadline = time.monotonic() + 45
            user_sent = False
            while time.monotonic() < deadline:
                try:
                    line = messages.get(timeout=min(1, deadline - time.monotonic()))
                except queue.Empty:
                    continue
                if line is None:
                    break
                message = json.loads(line)
                events.append(message)
                if message.get("type") == "control_response" and not user_sent:
                    send({"type": "user", "message": {"role": "user", "content": "Run the local permission canary."},
                          "parent_tool_use_id": None, "session_id": "", "uuid": str(uuid.uuid4())})
                    user_sent = True
                if message.get("type") == "control_request":
                    request = message["request"]
                    assert request["subtype"] == "can_use_tool", request
                    assert request["tool_name"] == api.tool, request
                    before = mcp_log.read_text() if mcp_log.exists() else ""
                    prompts.append({"request": request, "calls_before_prompt": before})
                    send({"type": "control_response", "response": {"subtype": "success",
                          "request_id": message["request_id"], "response": {"behavior": "deny",
                          "message": "Denied by the deterministic permission canary."}}})
                if message.get("type") == "result":
                    result = message
                    break
            proc.stdin.close()
            try:
                returncode = proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.terminate()
                returncode = proc.wait(timeout=5)
        calls = [json.loads(line) for line in mcp_log.read_text().splitlines()] if mcp_log.exists() else []
        summary = {"case": name, "server": server_name, "tool": target,
                   "expected_prompt": expect_prompt, "prompts": prompts, "calls": calls,
                   "result": result, "returncode": returncode,
                   "settings_sha256": hashlib.sha256(settings_file.read_bytes()).hexdigest()}
        (case / "events.json").write_text(json.dumps(events, indent=2) + "\n")
        (case / "api-requests.json").write_text(json.dumps(api.requests, indent=2) + "\n")
        (case / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        assert result is not None, f"{name}: no result; inspect {case}"
        assert returncode == 0, f"{name}: exit {returncode}; inspect {case}"
        assert not result.get("is_error"), summary
        assert any(api.tool in request.get("tools", []) for request in api.requests), "Target was not exposed to actual CLI"
        if expect_prompt:
            assert len(prompts) == 1 and not calls, summary
            assert prompts[0]["calls_before_prompt"] == "", summary
        else:
            assert not prompts and [call["tool"] for call in calls] == [target], summary
        return summary
    finally:
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait()
        api.shutdown()
        api.server_close()


def manifest_grants(path):
    """Read this manifest's simple server/tool lists without installing dependencies."""
    servers = {}
    server = None
    in_servers = False
    in_tools = False
    for line in path.read_text().splitlines():
        if line == "mcp_servers:":
            in_servers = True
        elif in_servers and line and not line.startswith(" "):
            break
        elif in_servers and line.startswith("  - id: "):
            server = line.partition(": ")[2]
            servers[server] = []
            in_tools = False
        elif in_servers and line == "    allowed_tools:":
            in_tools = True
        elif in_tools and line.startswith("      - "):
            servers[server].append(line[8:])
    assert servers and all(servers.values())
    return servers


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cli", default="claude")
    parser.add_argument("--server", default="all")
    parser.add_argument("--settings", type=Path, help="Actual generated settings to verify, instead of the candidate grants")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    resources = args.repo / "kit/camel-kit-core/src/main/resources"
    original = json.loads((resources / "templates/claude/settings.json").read_text())
    servers = manifest_grants(resources / "workflow/camel-kit-workflow.yaml")
    exact = json.loads(json.dumps(original))
    exact["permissions"]["allow"] = [item for item in original["permissions"]["allow"] if not item.startswith("mcp__")]
    exact["permissions"]["allow"] += [f"mcp__{server}__{tool}" for server, tools in servers.items() for tool in tools]
    if args.settings:
        exact = json.loads(args.settings.read_text())
        expected = {f"mcp__{server}__{tool}" for server, tools in servers.items() for tool in tools}
        actual = {rule for rule in exact["permissions"]["allow"] if rule.startswith("mcp__")}
        assert actual == expected, {"missing": sorted(expected - actual), "extra": sorted(actual - expected)}
    wildcard = json.loads(json.dumps(exact))
    wildcard["permissions"]["allow"] = [item for item in exact["permissions"]["allow"] if not item.startswith("mcp__")]
    wildcard["permissions"]["allow"] += [f"mcp__{server}__*" for server in servers]
    unlisted = "canary_unlisted_tool"
    summaries = []
    for server in servers if args.server == "all" else [args.server]:
        allowed = servers[server][0]
        removed = json.loads(json.dumps(exact))
        removed["permissions"]["allow"].remove(f"mcp__{server}__{allowed}")
        for label, settings, tool, prompt in [
            ("exact-allowed", exact, allowed, False),
            ("exact-unlisted", exact, unlisted, True),
            ("wildcard-control", wildcard, unlisted, False),
            ("removed-grant-control", removed, allowed, True),
        ]:
            summary = run_case(args.cli, args.output, server + "-" + label, settings,
                               server, tool, [allowed, unlisted], prompt)
            summaries.append(summary)
            print(server, label, "PASS", flush=True)
    result = {"cli_version": subprocess.check_output([args.cli, "--version"], text=True).strip(), "cases": summaries}
    (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "mcp":
        mcp_main(sys.argv[2], sys.argv[3:])
    else:
        main()
