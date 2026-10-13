#!/usr/bin/env python3
"""Opt-in CI emulator acceptance. Credentials travel only through adb stdin."""
import json
import os
import re
import subprocess
from pathlib import Path
from urllib.parse import urlsplit


def adb(*args, data=None, timeout=60):
    return subprocess.run(["adb", *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=True).stdout


def main():
    key = os.environ.get("SOLVIT_MODEL_API_KEY", "")
    address = os.environ.get("SOLVIT_MODEL_BASE_URL", "")
    model = os.environ.get("SOLVIT_MODEL_NAME", "")
    provider = os.environ.get("SOLVIT_MODEL_PROVIDER", "openai-compatible")
    url = urlsplit(address)
    if (len(key) < 8 or any(c in key for c in "\r\n") or
            url.scheme != "https" or not url.hostname or url.username or url.password or
            url.query or url.fragment or not model.strip() or len(model) > 256 or
            provider not in ("openai-compatible", "anthropic")):
        raise RuntimeError("Fresh credential, HTTPS base address and existing provider/model required")
    payload = json.dumps(dict(baseUrl=address, model=model, provider=provider, apiKey=key)).encode()
    output = Path("real-model-reports")
    output.mkdir(exist_ok=True)
    completed = False
    try:
        adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk", timeout=90)
        adb("install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", timeout=90)
        # Static shell syntax only: never interpolate config, user input or key.
        adb("shell", "run-as dev.solvit.app sh -c 'umask 077; mkdir -p files; cat > files/model-acceptance.json'", data=payload)
        instrument = adb("shell", "am", "instrument", "-w", "-e", "solvitRealModel", "true",
                         "-e", "class", "dev.solvit.app.RealModelAcceptanceTest",
                         "dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner", timeout=1200)
        completed = bool(re.search(rb"OK \(1 test\)", instrument))
        # Do not upload raw instrumentation/logcat: arbitrary provider errors can contain secrets.
    finally:
        try:
            raw = adb("shell", "run-as", "dev.solvit.app", "cat", "files/model-acceptance-result.json")
            report = json.loads(raw)
            sanitized = json.dumps(report, ensure_ascii=False, indent=2).replace(key, "[credential hidden]")
            (output / "result.json").write_text(sanitized)
            names = adb("shell", "run-as", "dev.solvit.app", "ls", "files").decode().splitlines()
            for name in names:
                if re.fullmatch(r"model-acceptance-[a-z-]+\.png", name):
                    (output / name).write_bytes(adb("exec-out", "run-as", "dev.solvit.app", "cat", "files/" + name))
        finally:
            # Disposable emulator: also erase any encrypted profile written before a process crash.
            subprocess.run(["adb", "shell", "pm", "clear", "dev.solvit.app"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=True)
    required = {"default-role", "applied-role", "switched-role", "new-chat-inherits-role",
                "reopened-chat-role", "visible-streaming", "javascript-tool-round-trip"}
    cases = {case["case"] for case in report.get("results", [])}
    if not completed or report.get("passed") is not True or report.get("realModel") is not True or not required <= cases:
        raise RuntimeError("Actual model acceptance did not pass; inspect sanitized result.json")
    print("Actual gateway role, visible streaming and JavaScript tool round-trip acceptance passed")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # No exception text/tracebacks: requests and subprocess failures may contain credential data.
        print("Real model acceptance failed: " + type(error).__name__ + "; inspect sanitized reports or preflight inputs")
        raise SystemExit(1)
