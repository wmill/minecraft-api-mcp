#!/usr/bin/env python3
import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from pathlib import Path


DEFAULT_BASE_URL = "http://localhost:7070"
DEFAULT_SERVER_READY_TIMEOUT = 300.0
DEFAULT_PLANNER_TIMEOUT = 300.0
DEFAULT_POLL_INTERVAL = 2.0


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request) as response:
        raw = response.read().decode("utf-8")
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw


def wait_for_api(base_url: str, timeout_seconds: float) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error = "server not ready"
    while time.monotonic() < deadline:
        try:
            response = request_json("GET", f"{base_url}/api/test")
            if response == "Server is running":
                return
            last_error = f"unexpected readiness response: {response!r}"
        except Exception as exc:  # noqa: BLE001
            last_error = str(exc)
        time.sleep(1.0)
    raise RuntimeError(
        f"Timed out waiting for API readiness at {base_url}/api/test after {timeout_seconds:.0f}s. "
        f"Last error: {last_error}"
    )


def is_api_ready(base_url: str) -> bool:
    try:
        return request_json("GET", f"{base_url}/api/test") == "Server is running"
    except Exception:  # noqa: BLE001
        return False


def print_step(message: str) -> None:
    print(f"[rail-debug] {message}", flush=True)


def run_command(command: list[str], cwd: Path, log_path: Path | None = None) -> None:
    print_step(f"Running: {' '.join(command)}")
    if log_path is None:
        subprocess.run(command, cwd=cwd, check=True)
        return

    with log_path.open("a", encoding="utf-8") as log_file:
        subprocess.run(command, cwd=cwd, check=True, stdout=log_file, stderr=subprocess.STDOUT)


def start_server(command: list[str], cwd: Path, log_path: Path) -> subprocess.Popen[str]:
    print_step(f"Starting server: {' '.join(command)}")
    log_file = log_path.open("a", encoding="utf-8")
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except Exception:
        log_file.close()
        raise
    process._codex_log_file = log_file  # type: ignore[attr-defined]
    return process


def stop_server(process: subprocess.Popen[str], leave_running: bool) -> None:
    log_file = getattr(process, "_codex_log_file", None)
    if leave_running:
        if log_file is not None:
            log_file.flush()
            log_file.close()
        print_step(f"Leaving server running with pid {process.pid}")
        return

    if process.poll() is None:
        print_step("Stopping server")
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)

    if log_file is not None:
        log_file.flush()
        log_file.close()


def tail_log(path: Path, line_count: int = 40) -> str:
    if not path.exists():
        return ""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join(lines[-line_count:])


def parse_json_arg(raw: str | None) -> dict | None:
    if raw is None:
        return None
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("--weight-overrides must be a JSON object")
    return value


def poll_planner(base_url: str, job_id: str, timeout_seconds: float, poll_interval: float) -> dict:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        response = request_json("GET", f"{base_url}/api/rail-plans/{job_id}")
        planning_job = response["planning_job"]
        status = planning_job["status"]
        phase = planning_job.get("phase", "")
        print_step(f"Planner status={status} phase={phase}")
        if status == "PLANNED":
            return response
        if status in {"FAILED", "CANCELLED"}:
            raise RuntimeError(f"Planner ended with status {status}: {planning_job.get('error_message', 'no error message')}")
        time.sleep(poll_interval)
    raise RuntimeError(f"Timed out waiting for planning job {job_id} after {timeout_seconds:.0f}s")


def ensure_output_dir(repo_root: Path, explicit_output_dir: str | None) -> Path:
    if explicit_output_dir:
        output_dir = Path(explicit_output_dir)
    else:
        timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
        output_dir = repo_root / "logs" / "rail-debug" / timestamp
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Start the local server, plan a rail route, and audit the resulting build.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--name", default="rail debug e2e")
    parser.add_argument("--description", default="Automated rail planning debug run")
    parser.add_argument("--world")
    parser.add_argument("--start-x", type=int, required=True)
    parser.add_argument("--start-y", type=int, required=True)
    parser.add_argument("--start-z", type=int, required=True)
    parser.add_argument("--end-x", type=int, required=True)
    parser.add_argument("--end-y", type=int, required=True)
    parser.add_argument("--end-z", type=int, required=True)
    parser.add_argument("--weight-overrides", help="JSON object passed through to the planner")
    parser.add_argument("--server-ready-timeout", type=float, default=DEFAULT_SERVER_READY_TIMEOUT)
    parser.add_argument("--planner-timeout", type=float, default=DEFAULT_PLANNER_TIMEOUT)
    parser.add_argument("--poll-interval", type=float, default=DEFAULT_POLL_INTERVAL)
    parser.add_argument("--output-dir")
    parser.add_argument("--reuse-server", action="store_true", help="Use an already-running API server instead of starting a new one")
    parser.add_argument("--leave-server-running", action="store_true", help="Do not stop the started server after the run")
    parser.add_argument("--skip-postgres", action="store_true", help="Skip docker compose startup for PostgreSQL")
    parser.add_argument(
        "--server-command",
        nargs="+",
        default=["./gradlew", "runServer"],
        help="Command used to start the Minecraft dev server",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    base_url = args.base_url.rstrip("/")
    output_dir = ensure_output_dir(repo_root, args.output_dir)
    server_log_path = output_dir / "server.log"
    summary_path = output_dir / "summary.json"
    summary: dict[str, object] = {
        "base_url": base_url,
        "output_dir": str(output_dir),
        "server_log": str(server_log_path),
        "status": "starting",
        "requested_route": {
            "start": {"x": args.start_x, "y": args.start_y, "z": args.start_z},
            "end": {"x": args.end_x, "y": args.end_y, "z": args.end_z},
            "world": args.world,
        },
    }
    server_process: subprocess.Popen[str] | None = None

    try:
        if is_api_ready(base_url):
            if not args.reuse_server:
                raise RuntimeError(
                    f"API server is already running at {base_url}. Stop it first or rerun with --reuse-server."
                )
            print_step(f"Reusing running API server at {base_url}")
            summary["server_mode"] = "reused"
        else:
            summary["server_mode"] = "started"
            if not args.skip_postgres:
                run_command(["docker", "compose", "up", "-d", "postgres"], repo_root)
            server_process = start_server(args.server_command, repo_root, server_log_path)
            summary["server_pid"] = server_process.pid
            wait_for_api(base_url, args.server_ready_timeout)

        weight_overrides = parse_json_arg(args.weight_overrides)

        create_payload = {"name": args.name, "description": args.description}
        if args.world:
            create_payload["world"] = args.world
        print_step("Creating build")
        build_response = request_json("POST", f"{base_url}/api/builds", create_payload)
        build = build_response["build"]
        build_id = build["id"]
        summary["build"] = build

        plan_payload = {
            "start_x": args.start_x,
            "start_y": args.start_y,
            "start_z": args.start_z,
            "end_x": args.end_x,
            "end_y": args.end_y,
            "end_z": args.end_z,
        }
        if args.world:
            plan_payload["world"] = args.world
        if weight_overrides:
            plan_payload["weight_overrides"] = weight_overrides

        print_step(f"Starting rail plan for build {build_id}")
        plan_response = request_json("POST", f"{base_url}/api/builds/{build_id}/plan-rail", plan_payload)
        planning_job = plan_response["planning_job"]
        job_id = planning_job["id"]
        summary["plan_request"] = plan_payload
        summary["planning_job_initial"] = planning_job

        final_plan_response = poll_planner(base_url, job_id, args.planner_timeout, args.poll_interval)
        summary["planning_job_final"] = final_plan_response["planning_job"]

        print_step(f"Auditing build {build_id}")
        audit_response = request_json("POST", f"{base_url}/api/builds/{build_id}/audit")
        summary["audit"] = audit_response
        error_count = audit_response.get("summary", {}).get("errors", 0)
        warning_count = audit_response.get("summary", {}).get("warnings", 0)
        print_step(f"Audit warnings={warning_count} errors={error_count}")

        if error_count:
            summary["status"] = "audit_failed"
            raise RuntimeError(f"Audit reported {error_count} error(s) for build {build_id}")

        summary["status"] = "passed"
        print_step("Rail debug run passed")
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        summary["status"] = "http_error"
        summary["error"] = f"HTTP {exc.code}: {body}"
        print(summary["error"], file=sys.stderr)
        return_code = 1
    except subprocess.CalledProcessError as exc:
        summary["status"] = "command_failed"
        summary["error"] = f"Command failed with exit code {exc.returncode}: {' '.join(exc.cmd)}"
        print(summary["error"], file=sys.stderr)
        return_code = 1
    except Exception as exc:  # noqa: BLE001
        summary["status"] = "failed"
        summary["error"] = str(exc)
        print(f"[rail-debug] ERROR: {exc}", file=sys.stderr)
        return_code = 1
    finally:
        if server_log_path.exists():
            summary["server_log_tail"] = tail_log(server_log_path)
        summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
        print_step(f"Wrote summary to {summary_path}")
        if server_process is not None:
            stop_server(server_process, args.leave_server_running)

    print(json.dumps(summary, indent=2, sort_keys=True))
    return return_code


if __name__ == "__main__":
    if sys.platform != "win32":
        signal.signal(signal.SIGINT, signal.default_int_handler)
    raise SystemExit(main())
