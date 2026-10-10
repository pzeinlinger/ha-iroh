import asyncio
import json

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
