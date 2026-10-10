from __future__ import annotations

from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.core import HomeAssistant

from . import HaIrohConfigEntry


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: HaIrohConfigEntry
) -> dict[str, Any]:
    b = entry.runtime_data
    try:
        devices = await b.list()
    except (ConnectionError, TimeoutError):
        devices = None
    return {
        "endpoint_id": b.endpoint_id,
        "running": b.running,
        "restarts": b.restarts,
        "settings": async_redact_data(b.settings, {"relay"}),
        "devices": devices,
    }
