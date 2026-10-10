# 08: Device Controls: full domain mapping + areas

**What to build:** Entity picker and panel support every domain in the spec table: switch/input_boolean, fan (percentage), cover (position or open/close, type by device_class), lock (auth required), climate (temperature control with step), scene/script/button (stateless routine), sensor/binary_sensor (read-only). Controls show the HA area name as zone. Completes M4.

**Blocked by:** 07 (Device Controls: light toggle end-to-end)

**Status:** ready-for-agent

- [ ] Each domain renders with the template/type from the spec table and sends the listed service calls
- [ ] Lock uses `setAuthRequired` on API 33+
- [ ] Climate target temperature uses the step from entity attributes
- [ ] `setZone` populated from `config/area_registry/list` + entity registry when available
- [ ] Sensors show state in status text and reject actions
- [ ] Unit test for the entity → Control mapping covering at least one entity per domain
