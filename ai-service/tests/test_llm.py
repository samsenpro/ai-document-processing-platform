import json

import httpx
import pytest

from app.llm.base import DisabledLlmService, LlmError, parse_json_object
from app.llm.factory import create_llm_service
from app.llm.openai_compatible import OpenAICompatibleLlmService
from tests.conftest import make_settings


def llm_with(handler) -> OpenAICompatibleLlmService:
    client = httpx.Client(base_url="http://llm.local/v1", transport=httpx.MockTransport(handler))
    return OpenAICompatibleLlmService("http://llm.local/v1", "test-model", client=client)


def chat_response(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_sends_an_openai_compatible_request():
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return chat_response('{"document_type": "INVOICE"}')

    result = llm_with(handler).complete_json("system prompt", "user prompt", operation="classification")
    body = json.loads(captured[0].content)
    assert captured[0].url.path == "/v1/chat/completions"
    assert body["model"] == "test-model"
    assert body["messages"][0] == {"role": "system", "content": "system prompt"}
    assert body["response_format"] == {"type": "json_object"}
    assert result == {"document_type": "INVOICE"}


def test_http_errors_and_timeouts_become_llm_errors():
    with pytest.raises(LlmError, match="HTTP 429"):
        llm_with(lambda r: httpx.Response(429, json={"error": "rate limited"})).complete("s", "u", operation="x")

    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(LlmError, match="timed out"):
        llm_with(timeout).complete("s", "u", operation="x")


def test_malformed_provider_responses_become_llm_errors():
    with pytest.raises(LlmError):
        llm_with(lambda r: httpx.Response(200, json={"unexpected": True})).complete("s", "u", operation="x")
    with pytest.raises(LlmError, match="empty"):
        llm_with(lambda r: chat_response("  ")).complete("s", "u", operation="x")


@pytest.mark.parametrize("raw", [
    '{"a": 1}',
    '```json\n{"a": 1}\n```',
    'Here is the JSON: {"a": 1} hope it helps',
])
def test_parse_json_object_tolerates_wrappers(raw):
    assert parse_json_object(raw) == {"a": 1}


@pytest.mark.parametrize("raw", ["no json", "[1, 2]", "{broken"])
def test_parse_json_object_rejects_invalid_output(raw):
    with pytest.raises(LlmError):
        parse_json_object(raw)


def test_factory_disables_the_llm_without_configuration():
    assert isinstance(create_llm_service(make_settings()), DisabledLlmService)
    configured = create_llm_service(make_settings(llm_base_url="http://ollama:11434/v1", llm_model="qwen2.5"))
    assert isinstance(configured, OpenAICompatibleLlmService) and configured.model == "qwen2.5"
