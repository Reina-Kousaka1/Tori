defmodule ToriEconomy.ApiTest do
  use ExUnit.Case, async: false
  import Plug.Test
  import Plug.Conn

  alias ToriEconomy.Api
  alias ToriEconomy.Persona.Mood

  setup do
    previous = System.get_env("TORI_ECONOMY_API_SECRET")
    System.put_env("TORI_ECONOMY_API_SECRET", String.duplicate("s", 32))

    on_exit(fn ->
      if previous,
        do: System.put_env("TORI_ECONOMY_API_SECRET", previous),
        else: System.delete_env("TORI_ECONOMY_API_SECRET")
    end)
  end

  test "unauthorized execution is rejected before parsing or touching state" do
    response = conn(:post, "/internal/economy/v1/execute", "not json") |> Api.call([])
    assert response.status == 401
    assert Jason.decode!(response.resp_body)["error"]["code"] == "FORBIDDEN"
  end

  test "private persona snapshot and allowlisted mood events need no Discord connection" do
    Mood.reset()
    on_exit(fn -> Mood.reset() end)

    anonymous = conn(:get, "/internal/persona/v1/snapshot") |> Api.call([])
    assert anonymous.status == 401

    event =
      conn(:post, "/internal/persona/v1/events", Jason.encode!(%{"event" => "volleyball_match"}))
      |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
      |> put_req_header("content-type", "application/json")
      |> Api.call([])

    assert event.status == 200
    assert Jason.decode!(event.resp_body)["mood"] == "competitive"

    snapshot =
      conn(:get, "/internal/persona/v1/snapshot")
      |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
      |> Api.call([])

    assert snapshot.status == 200
    assert Jason.decode!(snapshot.resp_body)["mood"] == "competitive"

    invalid =
      conn(:post, "/internal/persona/v1/events", Jason.encode!(%{"event" => "not_allowed"}))
      |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
      |> put_req_header("content-type", "application/json")
      |> Api.call([])

    assert invalid.status == 400
    assert Mood.snapshot().mood == :competitive
  end

  test "private persona rendering uses structured values and keeps serious copy neutral" do
    Mood.reset()
    Mood.record(:rare_drop)
    on_exit(fn -> Mood.reset() end)

    render = fn context ->
      conn(
        :post,
        "/internal/persona/v1/render",
        Jason.encode!(%{
          "key" => "shop.purchase.success",
          "context" => context,
          "variables" => %{"item_name" => "Bow", "amount" => 20}
        })
      )
      |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
      |> put_req_header("content-type", "application/json")
      |> Api.call([])
    end

    assert Jason.decode!(render.("shop").resp_body)["text"] =~ "✨"

    assert Jason.decode!(render.("administration").resp_body)["text"] ==
             "Purchased Bow for 20 Credits."

    assert render.("unknown").status == 400
  end

  test "malformed and numeric-snowflake requests are rejected without a write" do
    response = request("not json")
    assert response.status == 400

    invalid =
      Jason.encode!(%{
        "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
        "idempotency_key" => "discord-interaction:123456789012345678",
        "operation" => "daily.claim",
        "context" => %{"actor_user_id" => 123, "guild_id" => "234", "channel_id" => "345"},
        "args" => %{}
      })

    response = request(invalid)
    assert response.status == 400
    assert Jason.decode!(response.resp_body)["error"]["code"] == "INVALID_INPUT"

    invalid_amount =
      Jason.encode!(%{
        "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
        "idempotency_key" => "discord-interaction:123456789012345678",
        "operation" => "wallet.transfer",
        "context" => %{"actor_user_id" => "123", "guild_id" => "234", "channel_id" => "345"},
        "args" => %{"recipient_user_id" => "456", "amount" => "0"}
      })

    response = request(invalid_amount)
    assert response.status == 400
    assert Jason.decode!(response.resp_body)["error"]["code"] == "INVALID_AMOUNT"
  end

  test "valid mutations are read-only by default" do
    previous = System.get_env("TORI_ECONOMY_WRITE_ENABLED")
    previous_url = System.get_env("TORI_ECONOMY_DATABASE_URL")
    System.delete_env("TORI_ECONOMY_WRITE_ENABLED")
    System.delete_env("TORI_ECONOMY_DATABASE_URL")

    on_exit(fn ->
      if previous,
        do: System.put_env("TORI_ECONOMY_WRITE_ENABLED", previous),
        else: System.delete_env("TORI_ECONOMY_WRITE_ENABLED")

      if previous_url,
        do: System.put_env("TORI_ECONOMY_DATABASE_URL", previous_url),
        else: System.delete_env("TORI_ECONOMY_DATABASE_URL")
    end)

    valid =
      Jason.encode!(%{
        "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
        "idempotency_key" => "discord-interaction:123456789012345678",
        "operation" => "daily.claim",
        "context" => %{"actor_user_id" => "123", "guild_id" => "234", "channel_id" => "345"},
        "args" => %{}
      })

    response = request(valid)
    assert response.status == 403
    assert Jason.decode!(response.resp_body)["error"]["code"] == "READ_ONLY"
  end

  test "write gate refuses a non-test database even when explicitly enabled" do
    previous = System.get_env("TORI_ECONOMY_WRITE_ENABLED")
    previous_url = System.get_env("TORI_ECONOMY_DATABASE_URL")
    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    System.put_env("TORI_ECONOMY_DATABASE_URL", "postgresql://localhost:5432/tori")

    on_exit(fn ->
      if previous,
        do: System.put_env("TORI_ECONOMY_WRITE_ENABLED", previous),
        else: System.delete_env("TORI_ECONOMY_WRITE_ENABLED")

      if previous_url,
        do: System.put_env("TORI_ECONOMY_DATABASE_URL", previous_url),
        else: System.delete_env("TORI_ECONOMY_DATABASE_URL")
    end)

    valid =
      Jason.encode!(%{
        "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
        "idempotency_key" => "discord-interaction:123456789012345678",
        "operation" => "daily.claim",
        "context" => %{"actor_user_id" => "123", "guild_id" => "234", "channel_id" => "345"},
        "args" => %{}
      })

    response = request(valid)
    assert response.status == 403
    assert Jason.decode!(response.resp_body)["error"]["code"] == "READ_ONLY"
    assert_raise RuntimeError, fn -> ToriEconomy.WriteGate.validate_startup!() end
  end

  test "production write gate requires explicit cutover and scopes allowed operations" do
    keys = [
      "TORI_ECONOMY_WRITE_ENABLED",
      "TORI_ECONOMY_WRITE_MODE",
      "TORI_ECONOMY_DATABASE_URL",
      "TORI_ECONOMY_DATABASE_HOST",
      "TORI_ECONOMY_DATABASE_NAME",
      "TORI_ECONOMY_PRODUCTION_DATABASE_NAME",
      "TORI_ECONOMY_PRODUCTION_CUTOVER_ACK",
      "TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS"
    ]

    previous = Map.new(keys, &{&1, System.get_env(&1)})

    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    System.put_env("TORI_ECONOMY_WRITE_MODE", "production")
    System.delete_env("TORI_ECONOMY_DATABASE_URL")
    System.put_env("TORI_ECONOMY_DATABASE_HOST", "postgres")
    System.put_env("TORI_ECONOMY_DATABASE_NAME", "tori_main")
    System.put_env("TORI_ECONOMY_PRODUCTION_DATABASE_NAME", "tori_main")

    System.put_env(
      "TORI_ECONOMY_PRODUCTION_CUTOVER_ACK",
      "I_VERIFIED_BACKUP_RESTORE_SCHEMA_AND_EXCLUSIVE_WRITER_OWNERSHIP"
    )

    System.put_env("TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS", "daily.claim")

    on_exit(fn ->
      Enum.each(previous, fn
        {key, nil} -> System.delete_env(key)
        {key, value} -> System.put_env(key, value)
      end)
    end)

    assert :ok = ToriEconomy.WriteGate.validate_startup!()

    transfer =
      Jason.encode!(%{
        "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
        "idempotency_key" => "discord-interaction:123456789012345678",
        "operation" => "wallet.transfer",
        "context" => %{"actor_user_id" => "123", "guild_id" => "234", "channel_id" => "345"},
        "args" => %{"recipient_user_id" => "456", "amount" => "1"}
      })

    response = request(transfer)
    assert response.status == 403
    assert Jason.decode!(response.resp_body)["error"]["code"] == "READ_ONLY"

    System.put_env("TORI_ECONOMY_PRODUCTION_CUTOVER_ACK", "wrong")
    assert_raise RuntimeError, fn -> ToriEconomy.WriteGate.validate_startup!() end
  end

  test "disabled mode starts but rejects every mutation regardless of the enabled flag" do
    with_write_gate_env(fn ->
      System.put_env("TORI_ECONOMY_WRITE_MODE", "disabled")
      System.put_env("TORI_ECONOMY_DATABASE_URL", "postgresql://localhost/tori_test")

      for enabled <- ["true", "false"] do
        System.put_env("TORI_ECONOMY_WRITE_ENABLED", enabled)
        assert :ok = ToriEconomy.WriteGate.validate_startup!()
        assert {:error, "READ_ONLY"} = ToriEconomy.WriteGate.authorize("daily.claim")
        assert {:error, "READ_ONLY"} = ToriEconomy.WriteGate.authorize("wallet.transfer")
      end
    end)
  end

  test "test mode requires a configured test database and unknown enabled modes fail startup" do
    with_write_gate_env(fn ->
      System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
      System.put_env("TORI_ECONOMY_WRITE_MODE", "test")
      System.delete_env("TORI_ECONOMY_DATABASE_NAME")
      System.put_env("TORI_ECONOMY_DATABASE_URL", "postgresql://localhost/tori")
      assert_raise RuntimeError, fn -> ToriEconomy.WriteGate.validate_startup!() end
      assert {:error, "READ_ONLY"} = ToriEconomy.WriteGate.authorize("daily.claim")

      System.put_env("TORI_ECONOMY_DATABASE_URL", "postgresql://localhost/tori_test")
      assert :ok = ToriEconomy.WriteGate.validate_startup!()

      System.put_env("TORI_ECONOMY_WRITE_MODE", "unknown")
      assert_raise RuntimeError, fn -> ToriEconomy.WriteGate.validate_startup!() end
      assert {:error, "READ_ONLY"} = ToriEconomy.WriteGate.authorize("daily.claim")
    end)
  end

  defp with_write_gate_env(callback) do
    keys = [
      "TORI_ECONOMY_WRITE_ENABLED",
      "TORI_ECONOMY_WRITE_MODE",
      "TORI_ECONOMY_DATABASE_URL",
      "TORI_ECONOMY_DATABASE_NAME"
    ]

    previous = Map.new(keys, &{&1, System.get_env(&1)})

    try do
      callback.()
    after
      Enum.each(previous, fn
        {key, nil} -> System.delete_env(key)
        {key, value} -> System.put_env(key, value)
      end)
    end
  end

  defp request(body) do
    conn(:post, "/internal/economy/v1/execute", body)
    |> put_req_header("authorization", "Bearer " <> String.duplicate("s", 32))
    |> put_req_header("content-type", "application/json")
    |> Api.call([])
  end
end
