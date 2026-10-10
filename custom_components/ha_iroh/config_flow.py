from __future__ import annotations

from typing import Any

import probatio

from homeassistant.config_entries import (
    ConfigEntry,
    ConfigEntryState,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.const import CONF_NAME
from homeassistant.core import callback
from homeassistant.helpers.selector import (
    QrCodeSelector,
    QrCodeSelectorConfig,
    QrErrorCorrectionLevel,
)

from .const import CONF_RELAY, DEFAULTS, DOMAIN


class HaIrohConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        if user_input is None:
            return self.async_show_form(step_id="user")
        return self.async_create_entry(title="HA iroh", data=DEFAULTS)

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return HaIrohOptionsFlow()


class HaIrohOptionsFlow(OptionsFlow):
    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        return self.async_show_menu(step_id="init", menu_options=["pair", "settings"])

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=self.config_entry.options)
        if self.config_entry.state is not ConfigEntryState.LOADED:
            return self.async_abort(reason="bridge_unavailable")
        try:
            ticket = (await self.config_entry.runtime_data.pair())["ticket"]
        except (ConnectionError, TimeoutError, KeyError):
            return self.async_abort(reason="bridge_unavailable")
        return self.async_show_form(
            step_id="pair",
            data_schema=probatio.Schema(
                {
                    probatio.Optional("qr_code"): QrCodeSelector(
                        QrCodeSelectorConfig(
                            data=ticket,
                            scale=6,
                            error_correction_level=QrErrorCorrectionLevel.LOW,
                        )
                    )
                }
            ),
            description_placeholders={"uri": ticket},
        )

    async def async_step_settings(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors = {}
        cur = {**DEFAULTS, **self.config_entry.data, **self.config_entry.options}
        if user_input is not None:
            relay = user_input[CONF_RELAY].strip()
            urls = [u.strip() for u in relay.split(",")]
            if relay == "n0" or all(u.startswith(("http://", "https://")) for u in urls):
                return self.async_create_entry(data=user_input)
            errors[CONF_RELAY] = "invalid_relay"
            cur = user_input
        return self.async_show_form(
            step_id="settings",
            data_schema=probatio.Schema(
                {
                    probatio.Required(CONF_NAME, default=cur[CONF_NAME]): str,
                    probatio.Required(CONF_RELAY, default=cur[CONF_RELAY]): str,
                }
            ),
            errors=errors,
        )
