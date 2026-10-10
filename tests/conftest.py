import pytest


@pytest.fixture(autouse=True)
def _custom_integrations(enable_custom_integrations):
    yield
