"""HA iroh: runs ha-iroh-bridge and manages paired phones as devices."""

from __future__ import annotations

import asyncio
import shutil

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryNotReady
from homeassistant.helpers import device_registry as dr

from .bridge import BridgeProcess
from .const import DOMAIN

type HaIrohConfigEntry = ConfigEntry[BridgeProcess]


async def async_setup_entry(hass: HomeAssistant, entry: HaIrohConfigEntry) -> bool:
    def add_device(d: dict) -> None:
        dr.async_get(hass).async_get_or_create(
            config_entry_id=entry.entry_id,
            identifiers={(DOMAIN, d["endpoint_id"])},
            name=d["device_name"],
            manufacturer="ha-iroh",
            model="ha-iroh app",
        )

    bridge = BridgeProcess(hass, entry, add_device)
    try:
        await bridge.start()
        await asyncio.wait_for(bridge.ready.wait(), 30)
        for d in await bridge.list():
            add_device(d)
        entry.runtime_data = bridge
        if entry.title == "HA iroh":
            hass.config_entries.async_update_entry(
                entry, title=f"HA iroh ({bridge.endpoint_id[:8]})"
            )
    except BaseException as e:
        await bridge.stop()
        if isinstance(e, (OSError, TimeoutError, ConnectionError)):
            raise ConfigEntryNotReady(str(e) or "bridge did not become ready") from e
        raise
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    return True


async def _options_updated(hass: HomeAssistant, entry: HaIrohConfigEntry) -> None:
    # title updates also land here; only restart for real setting changes
    if entry.runtime_data.current_settings() != entry.runtime_data.settings:
        await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: HaIrohConfigEntry) -> bool:
    await entry.runtime_data.stop()
    return True


async def async_remove_entry(hass: HomeAssistant, entry: HaIrohConfigEntry) -> None:
    # key + allowlist: otherwise re-adding the integration restores every paired phone's access
    await hass.async_add_executor_job(shutil.rmtree, hass.config.path("ha_iroh"), True)


async def async_remove_config_entry_device(
    hass: HomeAssistant, entry: HaIrohConfigEntry, device: dr.DeviceEntry
) -> bool:
    if not hasattr(entry, "runtime_data"):
        return False
    ids = [i for d, i in device.identifiers if d == DOMAIN]
    try:
        return all([await entry.runtime_data.revoke(i) for i in ids])
    except (ConnectionError, TimeoutError):
        return False
