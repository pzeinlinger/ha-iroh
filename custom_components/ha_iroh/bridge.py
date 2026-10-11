"""Manages the ha-iroh-bridge subprocess (SPEC §6, §6b)."""

from __future__ import annotations

import asyncio
from collections import deque
from collections.abc import Callable
import json
import logging
import os
from pathlib import Path
import platform
import shutil

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant

from .const import CONF_BIND_PORT, CONF_RELAY, DEFAULTS

ARCHES = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}

_LOGGER = logging.getLogger(__name__)


class BridgeProcess:
    def __init__(
        self, hass: HomeAssistant, entry: ConfigEntry, on_paired: Callable[[dict], None]
    ) -> None:
        self.hass, self.entry, self.on_paired = hass, entry, on_paired
        self.endpoint_id: str | None = None
        self.restarts = 0
        self.settings: dict = {}
        self.ready = asyncio.Event()
        self._proc: asyncio.subprocess.Process | None = None
        self._task: asyncio.Task | None = None
        self._stopping = asyncio.Event()
        self._pending: deque[asyncio.Future] = deque()
        self._lock = asyncio.Lock()

    @property
    def running(self) -> bool:
        return self._proc is not None and self._proc.returncode is None

    def current_settings(self) -> dict:
        return {**DEFAULTS, **self.entry.data, **self.entry.options}

    def _write_config(self) -> str:
        self.settings = s = self.current_settings()
        d = self.hass.config.path("ha_iroh")
        relay = s[CONF_RELAY].strip()
        relay = "n0" if relay == "n0" else [u.strip() for u in relay.split(",") if u.strip()]
        port = self.hass.config.api.port if self.hass.config.api else 8123
        path = f"{d}/bridge.toml"
        # JSON strings/arrays are valid TOML
        lines = {
            "ha_addr": f"127.0.0.1:{port}",
            "secret_key_path": f"{d}/bridge.key",
            "allowlist_path": f"{d}/allowlist.toml",
            "relay": relay,
            "name": s[CONF_NAME],
            "bind_port": s[CONF_BIND_PORT],
        }
        os.makedirs(d, exist_ok=True)
        with open(path, "w") as f:
            f.write("".join(f"{k} = {json.dumps(v, ensure_ascii=False)}\n" for k, v in lines.items()))
        return path

    def _find_binary(self) -> str:
        # PATH wins (dev override); else the binary bundled in the HACS zip, copied
        # to <config> because the unzip loses the exec bit.
        if found := shutil.which("ha-iroh-bridge"):
            return found
        machine = platform.machine().lower()
        arch = ARCHES.get(machine)
        src = Path(__file__).parent / "bin" / f"ha-iroh-bridge-{arch}"
        if not arch or not src.is_file():
            raise FileNotFoundError(f"no bundled ha-iroh-bridge for architecture {machine!r}")
        dst = Path(self.hass.config.path("ha_iroh", "bin", "ha-iroh-bridge"))
        a, b = src.stat(), dst.stat() if dst.exists() else None
        if not b or (a.st_size, a.st_mtime) != (b.st_size, b.st_mtime):
            dst.parent.mkdir(parents=True, exist_ok=True)
            tmp = dst.with_suffix(".tmp")  # replace, not overwrite: an orphan bridge may still run dst (ETXTBSY)
            shutil.copy2(src, tmp)  # copy2 keeps mtime for the comparison above
            tmp.chmod(0o755)
            os.replace(tmp, dst)
        return str(dst)

    async def start(self) -> None:
        self._binary = await self.hass.async_add_executor_job(self._find_binary)
        self._config = await self.hass.async_add_executor_job(self._write_config)
        self._stopping.clear()
        self.ready.clear()
        self._task = self.entry.async_create_background_task(
            self.hass, self._run(), "ha_iroh bridge"
        )

    async def _spawn(self) -> asyncio.subprocess.Process:
        return await asyncio.create_subprocess_exec(
            self._binary,
            "run",
            "--config",
            self._config,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

    async def _run(self) -> None:
        backoff = 1
        while not self._stopping.is_set():
            err = None
            try:
                proc = await self._spawn()
                self._proc = proc
                if self._stopping.is_set():
                    proc.terminate()
                err = asyncio.create_task(self._log_stderr(proc.stderr))
                await self._read(proc.stdout)
                await proc.wait()
            except OSError as e:
                _LOGGER.error("cannot start bridge: %s", e)
            finally:
                if err:
                    err.cancel()
                if self.ready.is_set():
                    backoff = 1
                self.ready.clear()
                self._proc = None
                while self._pending:
                    fut = self._pending.popleft()
                    if not fut.done():
                        fut.set_exception(ConnectionError("bridge exited"))
            if self._stopping.is_set():
                return
            self.restarts += 1
            _LOGGER.warning("ha-iroh-bridge exited, restarting in %s s", backoff)
            try:
                await asyncio.wait_for(self._stopping.wait(), backoff)
            except TimeoutError:
                pass
            backoff = min(backoff * 2, 60)

    async def _read(self, stdout: asyncio.StreamReader) -> None:
        async for line in stdout:
            try:
                self._handle(json.loads(line))
            except Exception:  # noqa: BLE001 - one bad line must not kill the supervisor
                _LOGGER.exception("bad bridge line: %r", line)

    def _handle(self, msg: dict) -> None:
        if "event" not in msg:
            # response to the oldest unanswered command
            while self._pending:
                fut = self._pending.popleft()
                if not fut.done():
                    fut.set_result(msg)
                    break
        elif msg["event"] == "ready":
            self.endpoint_id = msg["endpoint_id"]
            self.ready.set()
        elif msg["event"] == "paired":
            self.on_paired(msg)

    async def _log_stderr(self, stderr: asyncio.StreamReader) -> None:
        async for line in stderr:
            text = line.decode(errors="replace").rstrip()
            warn = " WARN" in text or " ERROR" in text
            _LOGGER.log(logging.WARNING if warn else logging.INFO, "bridge: %s", text)

    async def command(self, obj: dict) -> dict:
        async with self._lock:
            if not self.running:
                raise ConnectionError("bridge not running")
            fut = asyncio.get_running_loop().create_future()
            self._pending.append(fut)
            self._proc.stdin.write(json.dumps(obj).encode() + b"\n")
            await self._proc.stdin.drain()
            return await asyncio.wait_for(fut, 10)

    async def pair(self) -> dict:
        return await self.command({"cmd": "pair"})

    async def list(self) -> list[dict]:
        return (await self.command({"cmd": "list"}))["devices"]

    async def revoke(self, endpoint_id: str) -> bool:
        r = await self.command({"cmd": "revoke", "endpoint_id": endpoint_id})
        return r["ok"] or r.get("error") == "not_found"

    async def stop(self) -> None:
        self._stopping.set()
        proc = self._proc
        if proc and proc.returncode is None:
            proc.stdin.close()
            try:
                await asyncio.wait_for(proc.wait(), 5)
            except TimeoutError:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), 3)
                except TimeoutError:
                    proc.kill()
        if self._task:
            await self._task
