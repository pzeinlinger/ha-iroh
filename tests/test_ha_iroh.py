import asyncio
import json
import os

import pytest
from unittest.mock import patch

from homeassistant.config_entries import ConfigEntryState
from homeassistant.helpers import device_registry as dr
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.ha_iroh import async_remove_config_entry_device
from custom_components.ha_iroh.const import DOMAIN

EID = "a" * 64
PEER = "b" * 64


class FakeProc:
    """Stands in for the bridge: answers admin commands, lets tests push events."""

    def __init__(self):
        self.stdout, self.stderr = asyncio.StreamReader(), asyncio.StreamReader()
        self.stdin = self
        self.written = []
        self.returncode = None
        self._done = asyncio.Event()
        self.stdout.feed_data(f'{{"event":"ready","endpoint_id":"{EID}"}}\n'.encode())

    # stdin
    def write(self, data):
        req = json.loads(data)
        self.written.append(req)
        resp = {"ok": True, "devices": [], "ticket": "ha-iroh://pair?t=x", "code": "c"}
        self.stdout.feed_data(json.dumps(resp).encode() + b"\n")

    async def drain(self):
        pass

    def close(self):
        self.terminate()

    def terminate(self):
        self.returncode = 0
        self.stdout.feed_eof()
        self.stderr.feed_eof()
        self._done.set()

    async def wait(self):
        await self._done.wait()
        return 0


async def _setup(hass):
    proc = FakeProc()

    async def spawn(*a, **kw):
        return proc

    p = [
        patch("asyncio.create_subprocess_exec", spawn),
        patch("shutil.which", return_value="/bin/ha-iroh-bridge"),
    ]
    for x in p:
        x.start()
    entry = MockConfigEntry(domain=DOMAIN, unique_id=DOMAIN, data={}, options={"relay": "n0", "name": "HA"})
    entry.add_to_hass(hass)
    await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry, proc, p


async def _teardown(hass, entry, p):
    await hass.config_entries.async_unload(entry.entry_id)
    for x in p:
        x.stop()


async def test_config_flow_creates_entry(hass):
    proc = FakeProc()

    async def spawn(*a, **kw):
        return proc

    with patch("asyncio.create_subprocess_exec", spawn), patch("shutil.which", return_value="/x"):
        r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
        assert r["type"] == "form"
        r = await hass.config_entries.flow.async_configure(r["flow_id"], {})
        assert r["type"] == "create_entry"
        await hass.async_block_till_done()
        entry = hass.config_entries.async_entries(DOMAIN)[0]
        assert entry.state is ConfigEntryState.LOADED
        assert EID[:8] in entry.title
        # second instance aborts
        r = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
        assert r["type"] == "abort"
        await hass.config_entries.async_unload(entry.entry_id)


async def test_paired_event_creates_device(hass):
    entry, proc, p = await _setup(hass)
    proc.stdout.feed_data(
        f'{{"event":"paired","endpoint_id":"{PEER}","device_name":"Pixel"}}\n'.encode()
    )
    await asyncio.sleep(0.05)
    dev = dr.async_get(hass).async_get_device_by_identifier((DOMAIN, PEER), entry.entry_id)
    assert dev and dev.name == "Pixel"
    await _teardown(hass, entry, p)


async def test_device_removal_revokes(hass):
    entry, proc, p = await _setup(hass)
    dev = dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id, identifiers={(DOMAIN, PEER)}
    )
    assert await async_remove_config_entry_device(hass, entry, dev) is True
    assert {"cmd": "revoke", "endpoint_id": PEER} in proc.written
    await _teardown(hass, entry, p)


async def test_revoke_not_found_is_success(hass):
    entry, proc, p = await _setup(hass)
    dev = dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id, identifiers={(DOMAIN, PEER)}
    )
    orig = proc.write

    def write(data):
        orig(data)
        proc.stdout._buffer.clear()  # replace the canned ok with not_found
        proc.stdout.feed_data(b'{"ok":false,"error":"not_found"}\n')

    proc.write = write
    assert await async_remove_config_entry_device(hass, entry, dev) is True
    await _teardown(hass, entry, p)


async def test_exit_while_pending_raises(hass):
    entry, proc, p = await _setup(hass)
    proc.write = lambda data: proc.terminate()  # dies instead of answering
    bridge = entry.runtime_data
    with pytest.raises(ConnectionError):
        await bridge.command({"cmd": "list"})
    await _teardown(hass, entry, p)


def _bridge(hass, options=None):
    from custom_components.ha_iroh import bridge

    entry = MockConfigEntry(domain=DOMAIN, data={}, options=options or {})
    return bridge, bridge.BridgeProcess(hass, entry, lambda m: None)


def test_bundled_binary_copied_and_executable(hass, monkeypatch, tmp_path):
    mod, b = _bridge(hass)
    (tmp_path / "bin").mkdir()
    (tmp_path / "bin" / "ha-iroh-bridge-aarch64").write_bytes(b"ELF")
    monkeypatch.setattr(mod, "__file__", str(tmp_path / "bridge.py"))
    monkeypatch.setattr(mod.platform, "machine", lambda: "arm64")
    monkeypatch.setattr(mod.shutil, "which", lambda n: None)
    hass.config.config_dir = str(tmp_path / "cfg")
    path = b._find_binary()
    assert path == hass.config.path("ha_iroh", "bin", "ha-iroh-bridge")
    assert open(path, "rb").read() == b"ELF" and os.access(path, os.X_OK)
    # unchanged: no recopy
    with patch.object(mod.shutil, "copy2", side_effect=AssertionError):
        assert b._find_binary() == path
    # changed source: replaced
    src = tmp_path / "bin" / "ha-iroh-bridge-aarch64"
    src.write_bytes(b"ELF2")
    os.utime(src, (1, 1))
    b._find_binary()
    assert open(path, "rb").read() == b"ELF2" and os.access(path, os.X_OK)


def test_missing_arch_binary_errors(hass, monkeypatch, tmp_path):
    mod, b = _bridge(hass)
    monkeypatch.setattr(mod, "__file__", str(tmp_path / "bridge.py"))
    monkeypatch.setattr(mod.platform, "machine", lambda: "x86_64")
    monkeypatch.setattr(mod.shutil, "which", lambda n: None)
    with pytest.raises(FileNotFoundError, match="x86_64"):
        b._find_binary()


@pytest.mark.parametrize("port,ok", [(0, True), (1024, True), (65535, True), (80, False), (70000, False)])
async def test_options_bind_port(hass, port, ok):
    entry = MockConfigEntry(domain=DOMAIN, data={}, options={})
    entry.add_to_hass(hass)
    r = await hass.config_entries.options.async_init(entry.entry_id)
    r = await hass.config_entries.options.async_configure(r["flow_id"], {"next_step_id": "settings"})
    r = await hass.config_entries.options.async_configure(
        r["flow_id"], {"name": "HA", "relay": "n0", "bind_port": port}
    )
    assert (r["type"] == "create_entry") is ok
    if not ok:
        assert r["errors"] == {"bind_port": "invalid_port"}


def test_bind_port_written_to_toml(hass, tmp_path):
    _, b = _bridge(hass, {"bind_port": 4242})
    hass.config.config_dir = str(tmp_path)
    assert "bind_port = 4242\n" in open(b._write_config()).read()
