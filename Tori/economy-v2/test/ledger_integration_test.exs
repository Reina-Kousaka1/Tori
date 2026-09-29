defmodule ToriEconomy.LedgerIntegrationTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Accounts, Repo, Sql}

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @moduletag skip: if(is_nil(@url), do: "set TORI_ECONOMY_TEST_DATABASE_URL to an isolated *_test database", else: false)

  setup_all do
      uri = URI.parse(@url)

      unless String.ends_with?(uri.path || "", "_test") do
        raise "Integration tests require a database name ending in _test"
      end

      start_supervised!({Repo, url: @url, pool_size: 10})
      :ok
  end

  defp user_id do
    random = :crypto.strong_rand_bytes(8) |> :binary.decode_unsigned()
    Integer.to_string(8_000_000_000_000_000_000 + rem(random, 1_000_000_000_000_000_000))
  end

  defp request_id, do: Ecto.UUID.generate()

  defp request(operation, actor, interaction, args \\ %{}) do
    context = %{
      "actor_user_id" => actor,
      "guild_id" => "234567890123456789",
      "channel_id" => "345678901234567890"
    }

    key = "discord-interaction:" <> interaction

    %{
      request_id: request_id(),
      operation: operation,
      context: context,
      args: args,
      idempotency_key: key,
      fingerprint: ToriEconomy.Contract.fingerprint(operation, context, args)
    }
  end

  test "daily commits once, replays after a lost response, and writes one ledger leg" do
    actor = user_id()
    interaction = user_id()
    original = request("daily.claim", actor, interaction)
    assert {:ok, first} = Accounts.execute(original)
    assert first["result"]["balance"] == "150"

    retry = %{original | request_id: request_id()}
    assert {:ok, second} = Accounts.execute(retry)
    assert second["result"] == first["result"]
    assert second["request_id"] == retry.request_id

    assert [[150]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1", [actor]).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key = $1",
               [original.idempotency_key]
             ).rows

    assert {:ok, cooldown} = Accounts.execute(request("daily.claim", actor, user_id()))
    assert cooldown["error"]["code"] == "COOLDOWN_ACTIVE"
  end

  test "same key with different payload conflicts without another mutation" do
    actor = user_id()
    key = user_id()
    assert {:ok, _} = Accounts.execute(request("daily.claim", actor, key))

    conflicting =
      request("wallet.transfer", actor, key, %{"recipient_user_id" => user_id(), "amount" => "1"})

    assert {:ok, response} = Accounts.execute(conflicting)
    assert response["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key = $1",
               [conflicting.idempotency_key]
             ).rows
  end

  test "parallel identical daily requests commit exactly once" do
    actor = user_id()
    original = request("daily.claim", actor, user_id())

    results =
      1..8
      |> Task.async_stream(
        fn _ -> Accounts.execute(%{original | request_id: request_id()}) end,
        max_concurrency: 8,
        timeout: 15_000
      )
      |> Enum.map(fn {:ok, {:ok, result}} -> result end)

    assert Enum.all?(results, &(&1["status"] == "ok"))
    assert Enum.uniq(Enum.map(results, & &1["result"])) == [hd(results)["result"]]
    assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [actor]).rows
    assert [[1]] =
             Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [original.idempotency_key]).rows
  end

  test "opposing transfers finish without a lock-order deadlock" do
    first = user_id()
    second = user_id()
    assert {:ok, _} = Accounts.execute(request("daily.claim", first, user_id()))
    assert {:ok, _} = Accounts.execute(request("daily.claim", second, user_id()))

    requests = [
      request("wallet.transfer", first, user_id(), %{"recipient_user_id" => second, "amount" => "50"}),
      request("wallet.transfer", second, user_id(), %{"recipient_user_id" => first, "amount" => "50"})
    ]

    results =
      requests
      |> Task.async_stream(&Accounts.execute/1, max_concurrency: 2, timeout: 15_000)
      |> Enum.map(fn {:ok, {:ok, result}} -> result end)

    assert Enum.all?(results, &(&1["status"] == "ok"))
    assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [first]).rows
    assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [second]).rows
    for item <- requests do
      assert [[2]] =
               Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [item.idempotency_key]).rows
    end
  end

  test "concurrent transfers serialize balance and ledger; insufficient funds does not debit" do
    sender = user_id()
    recipient = user_id()
    assert {:ok, _} = Accounts.execute(request("daily.claim", sender, user_id()))

    requests =
      for _ <- 1..2 do
        request("wallet.transfer", sender, user_id(), %{
          "recipient_user_id" => recipient,
          "amount" => "100"
        })
      end

    results =
      requests
      |> Task.async_stream(&Accounts.execute/1, max_concurrency: 2, timeout: 10_000)
      |> Enum.map(fn {:ok, {:ok, result}} -> result end)

    assert Enum.count(results, &(&1["status"] == "ok")) == 1
    assert Enum.count(results, &(&1["error"]["code"] == "INSUFFICIENT_FUNDS")) == 1

    assert [[50]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1", [sender]).rows

    assert [[100]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1", [recipient]).rows

    successful =
      Enum.find(requests, fn item ->
        [[count]] =
          Sql.query!(
            "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key = $1",
            [item.idempotency_key]
          ).rows

        count == 2
      end)

    assert successful

    assert [[0]] =
             Sql.query!(
               "SELECT sum(delta)::bigint FROM economy_v2_ledger_entries WHERE request_key = $1",
               [successful.idempotency_key]
             ).rows
  end
end
