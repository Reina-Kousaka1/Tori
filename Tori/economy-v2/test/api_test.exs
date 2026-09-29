defmodule ToriEconomy.ApiTest do
  use ExUnit.Case, async: false
  import Plug.Test
  import Plug.Conn

  alias ToriEconomy.Api

  setup do
    previous = System.get_env("TORI_ECONOMY_API_SECRET")
    System.put_env("TORI_ECONOMY_API_SECRET", String.duplicate("s", 32))

    on_exit(fn ->
      if previous, do: System.put_env("TORI_ECONOMY_API_SECRET", previous), else: System.delete_env("TORI_ECONOMY_API_SECRET")
    end)
  end

  test "unauthorized execution is rejected before parsing or touching state" do
    response = conn(:post, "/internal/economy/v1/execute", "not json") |> Api.call([])
    assert response.status == 401
    assert Jason.decode!(response.resp_body)["error"]["code"] == "FORBIDDEN"
  end

  test "malformed and numeric-snowflake requests are rejected without a write" do
    response = request("not json")
    assert response.status == 400

    invalid = Jason.encode!(%{
      "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
      "idempotency_key" => "discord-interaction:123456789012345678",
      "operation" => "daily.claim",
      "context" => %{"actor_user_id" => 123, "guild_id" => "234", "channel_id" => "345"},
      "args" => %{}
    })

    response = request(invalid)
    assert response.status == 400
    assert Jason.decode!(response.resp_body)["error"]["code"] == "INVALID_INPUT"
  end

  defp request(body) do
    conn(:post, "/internal/economy/v1/execute", body)
    |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
    |> put_req_header("content-type", "application/json")
    |> Api.call([])
  end
end
