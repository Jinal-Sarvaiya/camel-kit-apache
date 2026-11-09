#!/usr/bin/env python3
"""Linux packaged Knowledge stdio/SSE boundary check with a synthetic local index."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import socket
import subprocess
import threading
import time


def sockets(pid):
    inodes = set()
    for fd in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = fd.readlink().as_posix()
        except FileNotFoundError:
            continue
        if target.startswith("socket:["):
            inodes.add(target[8:-1])
    found = []
    for protocol in ["tcp", "tcp6", "udp", "udp6"]:
        for line in Path(f"/proc/{pid}/net/{protocol}").read_text().splitlines()[1:]:
            fields = line.split()
            if fields[9] in inodes:
                found.append({"protocol": protocol, "local": fields[1], "remote": fields[2],
                              "state": fields[3], "inode": fields[9]})
    return found


def syscall_evidence(case, mode, baseline_offline):
    """Inspect the whole child lifetime, including transient startup activity."""
    lines = (case / "syscalls.log").read_text().splitlines()
    execs = [line for line in lines if " execve(" in line or " execveat(" in line]
    connects = [line for line in lines if " connect(" in line]
    inet_connects = [line for line in connects if "sa_family=AF_INET" in line]
    service_connects = [line for line in inet_connects if not re.search(r"sin6?_port=htons\(0\)", line)]
    inet_sends = [line for line in lines if re.search(r" (sendto|sendmsg|sendmmsg)\(", line)
                  and ("<UDP" in line or "<TCP" in line)]
    inet_binds = [line for line in lines if " bind(" in line and "sa_family=AF_INET" in line]
    listeners = [line for line in lines if " listen(" in line]
    evidence = {"exec_calls": execs, "inet_connect_calls": inet_connects,
                "inet_service_connect_calls": service_connects,
                "inet_send_calls": inet_sends, "inet_bind_calls": inet_binds,
                "listen_calls": listeners,
                "note": "UDP port-zero connects inspect local interface routing; they do not send payloads. Netlink interface enumeration is recorded in the full trace."}
    (case / "syscall-summary.json").write_text(json.dumps(evidence, indent=2) + "\n")
    assert not service_connects and not inet_sends, evidence
    if mode == "stdio":
        assert not inet_binds and not listeners, evidence
    else:
        assert len(inet_binds) == 1 and len(listeners) == 1, evidence
    if not baseline_offline:
        assert len(execs) == 1, evidence  # The initial JVM, without any child executable.
    return evidence


def wait_item(items, description, timeout=60):
    try:
        result = items.get(timeout=timeout)
    except queue.Empty:
        raise AssertionError(f"Timed out waiting for {description}") from None
    if isinstance(result, Exception):
        raise result
    return result


def request(method, id=None, params=None):
    value = {"jsonrpc": "2.0", "method": method}
    if id is not None:
        value["id"] = id
    if params is not None:
        value["params"] = params
    return value


def init():
    return request("initialize", 1, {"protocolVersion": "2024-11-05", "capabilities": {},
                                    "clientInfo": {"name": "local-boundary-canary", "version": "1"}})


class Runner:
    def __init__(self, args, mode, port):
        self.case = args.output / mode
        self.case.mkdir()
        for name in ["tmp", "cache", "djl", "engine", "home"]:
            (self.case / name).mkdir()
        self.events, self.snapshots = [], []
        self.stdout = queue.Queue()
        env = {key: value for key, value in os.environ.items() if key in ["PATH", "LANG", "LC_ALL", "TZ"]}
        env.update({"DJL_CACHE_DIR": str(self.case / "djl"), "ENGINE_CACHE_DIR": str(self.case / "engine"),
                    "OMP_NUM_THREADS": "1"})
        java_cmd = [str(args.java), "-XX:ActiveProcessorCount=2", "-Donnx.threads=1",
                    "-Duser.home=" + str(self.case / "home"),
                    "-Djava.io.tmpdir=" + str(self.case / "tmp"),
                    "-Dknowledge.index.path=" + str(args.output / "index"),
                    "-Dknowledge.index.cache-dir=" + str(self.case / "cache")]
        if args.baseline_offline:
            java_cmd.append("-Dai.djl.offline=true")
        if mode == "sse":
            java_cmd += ["-Dquarkus.http.host-enabled=true", "-Dquarkus.http.host=127.0.0.1",
                         f"-Dquarkus.http.port={port}"]
        java_cmd += ["-jar", str(args.jar)]
        self.cmd = ["strace", "-f", "-s", "256", "-yy", "-e", "trace=%network,%process",
                    "-o", str(self.case / "syscalls.log"), *java_cmd]
        self.stderr = (self.case / "stderr.log").open("w")
        self.proc = subprocess.Popen(self.cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=self.stderr, text=True, env=env, cwd=self.case,
                                     start_new_session=True)
        def read():
            for line in self.proc.stdout:
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    value = {"stdout_text": line.rstrip()}
                self.stdout.put(value)
        threading.Thread(target=read, daemon=True).start()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            children = Path(f"/proc/{self.proc.pid}/task/{self.proc.pid}/children").read_text().split()
            if children:
                self.pid = int(children[0])
                break
            time.sleep(0.05)
        else:
            raise AssertionError("No traced JVM child")

    def capture(self, phase):
        self.snapshots.append({"phase": phase, "sockets": sockets(self.pid)})

    def send(self, message):
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()

    def stdio_result(self, id):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            item = wait_item(self.stdout, f"stdio response {id}", deadline - time.monotonic())
            self.events.append(item)
            if item.get("id") == id:
                assert "error" not in item, item
                return item["result"]
        raise AssertionError(f"Missing stdio result {id}")

    def close(self):
        if self.proc.poll() is None:
            self.proc.stdin.close()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGTERM)
                try:
                    self.proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(self.proc.pid, signal.SIGKILL)
                    self.proc.wait()
        self.stderr.close()
        (self.case / "events.json").write_text(json.dumps(self.events, indent=2) + "\n")
        (self.case / "sockets.json").write_text(json.dumps(self.snapshots, indent=2) + "\n")
        (self.case / "command.json").write_text(json.dumps(self.cmd, indent=2) + "\n")


def tool_content(result):
    assert not result.get("isError"), result
    content = result["content"]
    assert len(content) == 1 and content[0]["type"] == "text", result
    value = json.loads(content[0]["text"])
    assert "error" not in value, value
    return value


def stdio(args):
    runner = Runner(args, "stdio", None)
    try:
        runner.send(init())
        initialized = runner.stdio_result(1)
        runner.capture("initialized")
        runner.send(request("notifications/initialized"))
        runner.send(request("tools/list", 2))
        tools = runner.stdio_result(2)
        assert "camel_docs_search" in [tool["name"] for tool in tools["tools"]]
        runner.capture("tools-listed")
        runner.send(request("tools/call", 3, {"name": "camel_docs_search", "arguments": {
            "query": "Apache Camel timer component generates periodic messages", "max_results": 1}}))
        query = tool_content(runner.stdio_result(3))
        assert "runtime-canary" in json.dumps(query), query
        runner.capture("first-query-completed")
        assert not any(snapshot["sockets"] for snapshot in runner.snapshots), runner.snapshots
        return {"pid": runner.pid, "initialization": initialized, "tool_count": len(tools["tools"]),
                "query": query, "snapshots": runner.snapshots}
    finally:
        runner.close()


def sse(args):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    runner = Runner(args, "sse", port)
    conn = None
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise AssertionError("HTTP listener did not start")
        runner.capture("http-started")
        listeners = [s for s in runner.snapshots[-1]["sockets"] if s["state"] == "0A"]
        expected_port = f"{port:04X}"
        assert len(listeners) == 1 and listeners[0]["local"].endswith(":" + expected_port), listeners
        assert listeners[0]["local"].split(":")[0] in ["0100007F", "0000000000000000FFFF00000100007F"], listeners
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        conn.request("GET", "/mcp/sse", headers={"Accept": "text/event-stream"})
        response = conn.getresponse()
        assert response.status == 200, (response.status, response.read())
        events = queue.Queue()
        def read_sse():
            event, data = None, []
            try:
                while line := response.readline():
                    text = line.decode().rstrip("\r\n")
                    if not text:
                        if data:
                            events.put({"event": event, "data": "\n".join(data)})
                        event, data = None, []
                    elif text.startswith("event:"):
                        event = text[6:].strip()
                    elif text.startswith("data:"):
                        data.append(text[5:].strip())
            except Exception as e:
                events.put(e)
        threading.Thread(target=read_sse, daemon=True).start()
        endpoint_event = wait_item(events, "SSE endpoint")
        assert endpoint_event["event"] == "endpoint", endpoint_event
        endpoint = endpoint_event["data"]
        assert endpoint.startswith("/") and not endpoint.startswith("//"), endpoint
        runner.events.append(endpoint_event)
        def post(message):
            client = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
            client.request("POST", endpoint, body=json.dumps(message), headers={"Content-Type": "application/json"})
            reply = client.getresponse()
            status = reply.status
            body = reply.read().decode()
            client.close()
            assert status in [200, 202, 204], (status, body)
            if "id" not in message:
                return
            while True:
                event = wait_item(events, "SSE response")
                runner.events.append(event)
                value = json.loads(event["data"])
                if value.get("id") == message["id"]:
                    assert "error" not in value, value
                    return value["result"]
        initialized = post(init())
        post(request("notifications/initialized"))
        tools = post(request("tools/list", 2))
        query = tool_content(post(request("tools/call", 3, {"name": "camel_docs_search", "arguments": {
            "query": "Apache Camel timer component generates periodic messages", "max_results": 1}})))
        assert "runtime-canary" in json.dumps(query), query
        runner.capture("unauthenticated-query-completed")
        return {"pid": runner.pid, "port": port, "initialization": initialized, "tool_count": len(tools["tools"]),
                "query": query, "snapshots": runner.snapshots, "authorization_header_sent": False}
    finally:
        runner.close()
        if conn:
            conn.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--baseline-offline", action="store_true")
    args = parser.parse_args()
    args.jar = args.jar.resolve()
    args.java = args.java.resolve()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).with_name("FixtureIndex.java")
    subprocess.run([str(args.java.with_name("javac")), "-cp", str(args.jar), "-d", str(args.output), str(source)], check=True)
    subprocess.run([str(args.java), "-cp", str(args.output) + os.pathsep + str(args.jar), "FixtureIndex",
                    str(args.output / "index")], check=True)
    with args.jar.open("rb") as jar:
        jar_hash = hashlib.file_digest(jar, "sha256").hexdigest()
    result = {"jar": str(args.jar), "jar_sha256": jar_hash,
              "java": subprocess.run([str(args.java), "-version"], capture_output=True, text=True).stderr,
              "baseline_offline": args.baseline_offline}
    result["stdio"] = stdio(args)
    result["stdio"]["syscalls"] = syscall_evidence(args.output / "stdio", "stdio", args.baseline_offline)
    print("stdio PASS", flush=True)
    result["sse"] = sse(args)
    result["sse"]["syscalls"] = syscall_evidence(args.output / "sse", "sse", args.baseline_offline)
    print("sse PASS", flush=True)
    (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
