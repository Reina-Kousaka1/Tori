defmodule ToriEconomy.LedgerIntegrationTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Accounts, Api, Market, Queries, Repo, Sql, TestSchema}

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @moduletag skip: if(is_nil(@url), do: "set TORI_ECONOMY_TEST_DATABASE_URL to an isolated *_test database", else: false)

  setup_all do
    %{repo_pid: TestSchema.ensure_target!(@url)}
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

  defp api_call(payload) do
    Plug.Test.conn(:post, "/internal/economy/v1/execute", Jason.encode!(payload))
    |> Plug.Conn.put_req_header("authorization", "Bearer " <> String.duplicate("t", 32))
    |> Plug.Conn.put_req_header("content-type", "application/json")
    |> Api.call([])
  end

  defp api_payload(request) do
    %{
      "request_id" => request.request_id,
      "idempotency_key" => request.idempotency_key,
      "operation" => request.operation,
      "context" => request.context,
      "args" => request.args
    }
  end

  test "the application Repo uses the configured isolated test database" do
    assert TestSchema.config_mismatch(Repo.config(), @url) == nil

    expected_database = @url |> URI.parse() |> Map.fetch!(:path) |> String.trim_leading("/") |> URI.decode()
    assert [[^expected_database]] = Sql.query!("SELECT current_database()", []).rows
  end

  test "the Repo guard detects a different configured database" do
    assert TestSchema.config_mismatch(Keyword.put(Repo.config(), :database, "other_database"), @url) ==
             :database
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

  test "HTTP write gate is read-only by default, then test-only API writes replay safely" do
    previous_secret = System.get_env("TORI_ECONOMY_API_SECRET")
    previous_writes = System.get_env("TORI_ECONOMY_WRITE_ENABLED")
    previous_mode = System.get_env("TORI_ECONOMY_WRITE_MODE")
    previous_url = System.get_env("TORI_ECONOMY_DATABASE_URL")
    System.put_env("TORI_ECONOMY_API_SECRET", String.duplicate("t", 32))
    System.delete_env("TORI_ECONOMY_WRITE_ENABLED")
    System.put_env("TORI_ECONOMY_WRITE_MODE", "test")
    System.put_env("TORI_ECONOMY_DATABASE_URL", @url)

    on_exit(fn ->
      if previous_secret, do: System.put_env("TORI_ECONOMY_API_SECRET", previous_secret), else: System.delete_env("TORI_ECONOMY_API_SECRET")
      if previous_writes, do: System.put_env("TORI_ECONOMY_WRITE_ENABLED", previous_writes), else: System.delete_env("TORI_ECONOMY_WRITE_ENABLED")
      if previous_mode, do: System.put_env("TORI_ECONOMY_WRITE_MODE", previous_mode), else: System.delete_env("TORI_ECONOMY_WRITE_MODE")
      if previous_url, do: System.put_env("TORI_ECONOMY_DATABASE_URL", previous_url), else: System.delete_env("TORI_ECONOMY_DATABASE_URL")
    end)

    actor = user_id()
    original = request("daily.claim", actor, user_id())
    locked = api_call(api_payload(original))
    assert locked.status == 403
    assert Jason.decode!(locked.resp_body)["error"]["code"] == "READ_ONLY"
    assert [[0]] = Sql.query!("SELECT count(*) FROM economy_accounts WHERE user_id=$1", [actor]).rows
    assert [[0]] = Sql.query!("SELECT count(*) FROM economy_v2_requests WHERE idempotency_key=$1", [original.idempotency_key]).rows

    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    first = api_call(api_payload(original))
    assert first.status == 200
    first_body = Jason.decode!(first.resp_body)
    assert first_body["result"]["credits_awarded"] == "150"

    retry = %{original | request_id: request_id()}
    second = api_call(api_payload(retry))
    second_body = Jason.decode!(second.resp_body)
    assert second.status == 200
    assert second_body["result"] == first_body["result"]
    assert second_body["request_id"] == retry.request_id
    assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [actor]).rows
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [original.idempotency_key]).rows
  end

  test "persisted daily interaction replays after the Repo process restarts", %{repo_pid: old_repo} do
    actor = user_id()
    original = request("daily.claim", actor, user_id())
    assert {:ok, first} = Accounts.execute(original)
    assert Process.alive?(old_repo)
    assert :ok = Supervisor.terminate_child(ToriEconomy.Supervisor, Repo)
    assert {:ok, new_repo} = Supervisor.restart_child(ToriEconomy.Supervisor, Repo)
    assert new_repo != old_repo

    assert {:ok, replay} = Accounts.execute(%{original | request_id: request_id()})
    assert replay["result"] == first["result"]
    assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [actor]).rows
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [original.idempotency_key]).rows
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

  test "legacy last_daily_at remains authoritative and cooldown denial changes no persisted state" do
    actor = user_id()
    interaction = user_id()
    now = System.system_time(:millisecond)
    day_ms = ToriEconomy.DailyCooldown.duration_ms()
    legacy_last_claim = now - (day_ms - 300_000)
    fixed_updated_at = ~U[2020-01-02 03:04:05Z]

    Sql.query!(
      """
      INSERT INTO economy_accounts(user_id, balance, last_daily_at, updated_at)
      VALUES ($1, $2, $3, $4)
      """,
      [actor, 725, legacy_last_claim, fixed_updated_at]
    )

    before =
      Sql.query!(
        "SELECT balance, last_daily_at, updated_at::text FROM economy_accounts WHERE user_id=$1",
        [actor]
      ).rows

    daily_request = request("daily.claim", actor, interaction)
    assert {:ok, denied} = Accounts.execute(daily_request)
    assert denied["error"]["code"] == "COOLDOWN_ACTIVE"
    retry_after = denied["error"]["details"]["retry_after_ms"]
    assert is_integer(retry_after) and retry_after > 0 and retry_after <= 300_000

    after_attempt =
      Sql.query!(
        "SELECT balance, last_daily_at, updated_at::text FROM economy_accounts WHERE user_id=$1",
        [actor]
      ).rows

    assert after_attempt == before
    assert [[0]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_requests WHERE idempotency_key=$1",
               [daily_request.idempotency_key]
             ).rows

    assert [[0]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE user_id=$1",
               [actor]
             ).rows
  end

  test "claim after an existing legacy 24-hour cooldown awards exactly 150" do
    actor = user_id()
    last_claim =
      System.system_time(:millisecond) - ToriEconomy.DailyCooldown.duration_ms() - 1_000

    Sql.query!(
      "INSERT INTO economy_accounts(user_id, balance, last_daily_at) VALUES ($1, $2, $3)",
      [actor, 725, last_claim]
    )

    assert {:ok, result} = Accounts.execute(request("daily.claim", actor, user_id()))
    assert result["result"]["credits_awarded"] == "150"
    assert result["result"]["balance"] == "875"

    assert [[875, stored_last_claim]] =
             Sql.query!(
               "SELECT balance, last_daily_at FROM economy_accounts WHERE user_id=$1",
               [actor]
             ).rows

    assert stored_last_claim >= last_claim + ToriEconomy.DailyCooldown.duration_ms()
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

  test "identical transfer requests in parallel debit and credit exactly once" do
    sender = user_id()
    recipient = user_id()
    daily_request = request("daily.claim", sender, user_id())
    assert {:ok, _} = Accounts.execute(daily_request)
    original = request("wallet.transfer", sender, user_id(), %{"recipient_user_id" => recipient, "amount" => "25"})

    results =
      1..8
      |> Task.async_stream(fn _ -> Accounts.execute(%{original | request_id: request_id()}) end,
        max_concurrency: 8, timeout: 15_000)
      |> Enum.map(fn {:ok, {:ok, result}} -> result end)

    assert Enum.all?(results, &(&1["status"] == "ok"))
    assert Enum.uniq(Enum.map(results, & &1["result"])) == [hd(results)["result"]]
    assert [[125]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [sender]).rows
    assert [[25]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [recipient]).rows
    assert [[2]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [original.idempotency_key]).rows
    assert [["DAILY_CLAIM"]] = Sql.query!("SELECT reason_code FROM economy_v2_ledger_entries WHERE user_id=$1 AND request_key=$2", [sender, daily_request.idempotency_key]).rows
    assert [["TRANSFER_OUT"]] = Sql.query!("SELECT reason_code FROM economy_v2_ledger_entries WHERE user_id=$1 AND request_key=$2", [sender, original.idempotency_key]).rows
    assert [["TRANSFER_IN"]] = Sql.query!("SELECT reason_code FROM economy_v2_ledger_entries WHERE user_id=$1 AND request_key=$2", [recipient, original.idempotency_key]).rows
  end

  test "insufficient or self transfers do not create wallets or ledger legs" do
    sender = user_id()
    recipient = user_id()
    insufficient = request("wallet.transfer", sender, user_id(), %{"recipient_user_id" => recipient, "amount" => "1"})
    assert {:ok, result} = Accounts.execute(insufficient)
    assert result["error"]["code"] == "INSUFFICIENT_FUNDS"
    assert [[0]] = Sql.query!("SELECT count(*) FROM economy_accounts WHERE user_id IN ($1,$2)", [sender, recipient]).rows
    assert [[0]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [insufficient.idempotency_key]).rows

    self_transfer = request("wallet.transfer", sender, user_id(), %{"recipient_user_id" => sender, "amount" => "1"})
    assert {:ok, self_result} = Accounts.execute(self_transfer)
    assert self_result["error"]["code"] == "INVALID_TARGET"
    assert [[0]] = Sql.query!("SELECT count(*) FROM economy_accounts WHERE user_id=$1", [sender]).rows
  end

  test "ledger failure rolls the entire transfer and idempotency record back" do
    sender = user_id()
    recipient = user_id()
    assert {:ok, _} = Accounts.execute(request("daily.claim", sender, user_id()))
    request = request("wallet.transfer", sender, user_id(), %{"recipient_user_id" => recipient, "amount" => "25"})
    suffix = String.replace(Ecto.UUID.generate(), "-", "")
    function_name = "reject_transfer_in_" <> suffix
    trigger_name = "reject_transfer_in_trigger_" <> suffix

    Sql.query!("""
      CREATE FUNCTION #{function_name}() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        IF NEW.reason_code = 'TRANSFER_IN' THEN RAISE EXCEPTION 'test rollback'; END IF;
        RETURN NEW;
      END $$
      """)
    Sql.query!("CREATE TRIGGER #{trigger_name} BEFORE INSERT ON economy_v2_ledger_entries FOR EACH ROW EXECUTE FUNCTION #{function_name}()")

    try do
      assert {:error, "SERVICE_UNAVAILABLE"} = Accounts.execute(request)
      assert [[150]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [sender]).rows
      assert [[0]] = Sql.query!("SELECT count(*) FROM economy_accounts WHERE user_id=$1", [recipient]).rows
      assert [[0]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1", [request.idempotency_key]).rows
      assert [[0]] = Sql.query!("SELECT count(*) FROM economy_v2_requests WHERE idempotency_key=$1", [request.idempotency_key]).rows
    after
      Sql.query!("DROP TRIGGER IF EXISTS #{trigger_name} ON economy_v2_ledger_entries")
      Sql.query!("DROP FUNCTION IF EXISTS #{function_name}()")
    end
  end

  test "inventory, catalog and leaderboard reads use the existing Tori tables" do
    actor = user_id()
    target = user_id()
    product = "api_test_" <> String.slice(Ecto.UUID.generate(), 0, 8)

    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,0),($2,4821)", [actor, target])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'fish',3)", [target])
    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')", [target])

    Sql.query!(
      """
      INSERT INTO economy_market_products(product_id,name,description,category,current_price,base_price,
        minimum_price,maximum_price,volatility,stock,available,rarity,tags,created_at,updated_at,next_price_at)
      VALUES ($1,'API read fixture','Read-only test product','api_test',314,314,100,500,0,-1,TRUE,'test',
        ARRAY[]::TEXT[],now(),now(),now()+interval '1 day')
      """,
      [product]
    )

    Sql.query!(
      "INSERT INTO economy_market_price_history(product_id,price,changed_at,reason) VALUES ($1,314,now(),'ADMIN_CHANGE')",
      [product]
    )

    on_exit(fn ->
      Sql.query!("DELETE FROM economy_equipment WHERE user_id=$1", [target])
      Sql.query!("DELETE FROM economy_inventory WHERE user_id=$1", [target])
      Sql.query!("DELETE FROM economy_accounts WHERE user_id IN ($1,$2)", [actor, target])
      Sql.query!("DELETE FROM economy_market_price_history WHERE product_id=$1", [product])
      Sql.query!("DELETE FROM economy_market_products WHERE product_id=$1", [product])
    end)

    assert {:ok, inventory} = Queries.execute(request("inventory.list", actor, user_id()) |> put_in([:context, "target_user_id"], target))
    assert inventory["result"]["user_id"] == target
    assert [%{"item_id" => "fish", "quantity" => "3", "name" => "fish"}] =
      Enum.map(inventory["result"]["items"], &Map.take(&1, ["item_id", "quantity", "name"]))

    assert {:ok, profile} = Queries.execute(request("profile.snapshot", actor, user_id()) |> put_in([:context, "target_user_id"], target))
    assert profile["result"]["balance"] == "4821"
    assert [%{"item_id" => "fish", "quantity" => "3"}] =
      Enum.map(profile["result"]["items"], &Map.take(&1, ["item_id", "quantity"]))
    assert profile["result"]["equipment"] == [%{"slot" => "rod", "item_id" => "fishing_rod"}]

    assert {:ok, catalog} = Queries.execute(request("shop.catalog", actor, user_id(), %{"category" => "api_test"}))
    assert [%{"product_id" => ^product, "current_price" => "314", "effective_price" => "314", "stock" => "-1", "available" => true}] =
             catalog["result"]["products"]

    assert {:ok, detail} = Market.execute(request("market.product", actor, user_id(), %{"product_id" => product}))
    assert detail["result"]["current_price"] == "314"
    assert detail["result"]["stock"] == "-1"
    assert {:ok, history} = Market.execute(request("market.history", actor, user_id(), %{"product_id" => product}))
    assert [%{"price" => "314", "reason" => "ADMIN_CHANGE"}] = history["result"]["points"] |> Enum.map(&Map.drop(&1, ["changed_at"]))

    assert {:ok, leaderboard} = Queries.execute(request("wallet.leaderboard", actor, user_id(), %{"limit" => 100}))
    assert Enum.any?(leaderboard["result"]["entries"], &(&1["user_id"] == target and &1["balance"] == "4821"))

    previous_secret = System.get_env("TORI_ECONOMY_API_SECRET")
    previous_writes = System.get_env("TORI_ECONOMY_WRITE_ENABLED")
    System.put_env("TORI_ECONOMY_API_SECRET", String.duplicate("r", 32))
    System.delete_env("TORI_ECONOMY_WRITE_ENABLED")

    on_exit(fn ->
      if previous_secret, do: System.put_env("TORI_ECONOMY_API_SECRET", previous_secret), else: System.delete_env("TORI_ECONOMY_API_SECRET")
      if previous_writes, do: System.put_env("TORI_ECONOMY_WRITE_ENABLED", previous_writes), else: System.delete_env("TORI_ECONOMY_WRITE_ENABLED")
    end)

    for {operation, args} <- [
          {"inventory.list", %{}},
          {"profile.snapshot", %{}},
          {"market.product", %{"product_id" => product}},
          {"market.history", %{"product_id" => product}},
          {"shop.catalog", %{"category" => "api_test"}},
          {"wallet.leaderboard", %{"limit" => 100}}
        ] do
      api_payload = %{
        "request_id" => request_id(),
        "idempotency_key" => nil,
        "operation" => operation,
        "context" => %{"actor_user_id" => actor, "target_user_id" => target,
          "guild_id" => "234567890123456789", "channel_id" => "345678901234567890"},
        "args" => args
      }

      response =
        Plug.Test.conn(:post, "/internal/economy/v1/execute", Jason.encode!(api_payload))
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> String.duplicate("r", 32))
        |> Plug.Conn.put_req_header("content-type", "application/json")
        |> ToriEconomy.Api.call([])

      assert response.status == 200
      assert Jason.decode!(response.resp_body)["status"] == "ok"
    end

    assert [[0]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [actor]).rows
    assert [[3]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='fish'", [target]).rows
  end
end
