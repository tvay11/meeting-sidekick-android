import json
import asyncio
import os
import sys
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import Settings, create_app


def settings(**overrides):
    return Settings(api_key="test-provider-key", access_token="test-access-token-at-least-24", **overrides)


AUTH = {"Authorization": "Bearer test-access-token-at-least-24"}


def upstream(request):
    body = json.loads(request.content)
    assert request.url == "https://api.deepseek.com/chat/completions"
    assert request.headers["authorization"] == "Bearer test-provider-key"
    assert body["stream"] is True
    assert body["messages"][0]["role"] == "system"
    return httpx.Response(200, text='data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: {"choices":[{"delta":{"content":" there"}}]}\n\ndata: [DONE]\n\n')


def test_auth_required_and_health_does_not_expose_secrets():
    with TestClient(create_app(settings(), httpx.MockTransport(upstream))) as client:
        assert client.post("/v1/replies", json={"question": "Hello"}).status_code == 401
        assert client.post("/v1/replies", json={"question": "Hello"}, headers={"Authorization": "Bearer bad"}).status_code == 401
        response = client.get("/health")
        assert response.json() == {"status": "ready"}
        assert "test-provider" not in response.text


def test_streamed_answer_and_validation():
    with TestClient(create_app(settings(), httpx.MockTransport(upstream))) as client:
        response = client.post("/v1/replies", json={"question": "What can I say?", "transcript": "Meeting text"}, headers=AUTH)
        assert response.status_code == 200
        assert 'event: delta' in response.text
        assert '"text": "Hello"' in response.text
        assert '"text": " there"' in response.text
        assert response.text.endswith("event: done\ndata: {}\n\n")
        for payload in ({}, {"question": "   "}, {"question": "x" * 2001}, {"question": "x", "upstream": "https://example.com"}):
            assert client.post("/v1/replies", json=payload, headers=AUTH).status_code == 422


def test_upstream_errors_are_sanitized():
    transport = httpx.MockTransport(lambda _: httpx.Response(401, text="secret-provider-token"))
    with TestClient(create_app(settings(), transport)) as client:
        response = client.post("/v1/replies", json={"question": "Hi"}, headers=AUTH)
        assert "event: error" in response.text
        assert "secret-provider-token" not in response.text
        assert "event: done" not in response.text


def test_truncated_upstream_is_error_not_success():
    transport = httpx.MockTransport(lambda _: httpx.Response(200, text='data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'))
    with TestClient(create_app(settings(), transport)) as client:
        response = client.post("/v1/replies", json={"question": "Hi"}, headers=AUTH)
        assert "event: error" in response.text
        assert "event: done" not in response.text


def test_rate_limit_and_oversized_body():
    with TestClient(create_app(settings(requests_per_minute=1), httpx.MockTransport(upstream))) as client:
        assert client.post("/v1/replies", json={"question": "Hi"}, headers=AUTH).status_code == 200
        assert client.post("/v1/replies", json={"question": "Hi"}, headers=AUTH).status_code == 429
        assert client.post("/v1/replies", content=b"x" * 131073, headers=AUTH).status_code == 413


def test_missing_credentials_fail_closed(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    monkeypatch.delenv("APP_ACCESS_TOKEN", raising=False)
    with pytest.raises(ValueError):
        Settings.from_env()


def test_fragmented_network_stream_preserves_unicode():
    class Fragmented(httpx.AsyncByteStream):
        async def __aiter__(self):
            data = 'data: {"choices":[{"delta":{"content":"Café"}}]}\r\n\r\ndata: [DONE]\r\n\r\n'.encode()
            for byte in data:
                yield bytes([byte])
    transport = httpx.MockTransport(lambda _: httpx.Response(200, stream=Fragmented()))
    with TestClient(create_app(settings(), transport)) as client:
        response = client.post("/v1/replies", json={"question": "Hi"}, headers=AUTH)
        assert '"text": "Café"' in response.text
        assert "event: done" in response.text


def test_cancelled_generation_releases_concurrency_slot():
    async def run():
        started = asyncio.Event()
        released = asyncio.Event()

        class Waiting(httpx.AsyncByteStream):
            async def __aiter__(self):
                started.set()
                try:
                    await asyncio.Event().wait()
                    yield b""
                finally:
                    released.set()

        calls = 0
        def handle(request):
            nonlocal calls
            calls += 1
            return httpx.Response(200, stream=Waiting()) if calls == 1 else upstream(request)

        app = create_app(settings(), httpx.MockTransport(handle))
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                pending = asyncio.create_task(client.post("/v1/replies", json={"question": "One"}, headers=AUTH))
                await asyncio.wait_for(started.wait(), 2)
                second = await client.post("/v1/replies", json={"question": "Two"}, headers=AUTH)
                assert second.status_code == 429
                pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)
                assert released.is_set()
                third = await client.post("/v1/replies", json={"question": "Three"}, headers=AUTH)
                assert third.status_code == 200 and "event: done" in third.text
    asyncio.run(run())
