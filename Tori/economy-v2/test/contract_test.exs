defmodule ToriEconomy.ContractTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Contract

  @base %{
    "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
    "idempotency_key" => "discord-interaction:123456789012345678",
    "operation" => "daily.claim",
    "context" => %{
      "actor_user_id" => "123456789012345678",
      "guild_id" => "234567890123456789",
      "channel_id" => "345678901234567890"
    },
    "args" => %{}
  }

  test "accepts a global-user daily request with string snowflakes" do
    assert {:ok, request} = Contract.validate(@base)
    assert request.context["actor_user_id"] == "123456789012345678"
    assert byte_size(request.fingerprint) == 64
  end

  test "profile snapshot is a read with no mutation key or command-specific arguments" do
    profile = %{@base | "operation" => "profile.snapshot", "idempotency_key" => nil}
    assert {:ok, %{operation: "profile.snapshot"}} = Contract.validate(profile)
    assert {:error, "INVALID_INPUT"} = Contract.validate(put_in(profile, ["args", "level"], 7))
  end

  test "market reads require a safe product ID and bounded history" do
    product = %{@base | "operation" => "market.product", "idempotency_key" => nil,
                         "args" => %{"product_id" => "fishing_rod"}}
    assert {:ok, _} = Contract.validate(product)
    assert {:error, "INVALID_INPUT"} = Contract.validate(put_in(product, ["args", "product_id"], "bad/id"))

    history = %{product | "operation" => "market.history",
                         "args" => %{"product_id" => "fishing_rod", "limit" => 100}}
    assert {:ok, _} = Contract.validate(history)
    assert {:error, "INVALID_INPUT"} = Contract.validate(put_in(history, ["args", "limit"], 101))
  end

  test "fingerprint ignores field order and tracing request id" do
    assert {:ok, first} = Contract.validate(@base)
    reordered = Map.put(@base, "context", Map.new(Enum.reverse(Map.to_list(@base["context"]))))

    assert {:ok, second} =
             Contract.validate(%{
               reordered
               | "request_id" => "00000000-0000-0000-0000-000000000001"
             })

    assert first.fingerprint == second.fingerprint
  end

  test "rejects numeric snowflakes, unknown operations, unknown fields and missing mutation key" do
    assert {:error, "INVALID_INPUT"} =
             Contract.validate(put_in(@base, ["context", "actor_user_id"], 123))

    assert {:error, "INVALID_INPUT"} = Contract.validate(%{@base | "operation" => "market.buy"})
    assert {:error, "INVALID_INPUT"} = Contract.validate(Map.put(@base, "unexpected", true))
    assert {:error, "INVALID_INPUT"} = Contract.validate(Map.delete(@base, "idempotency_key"))
  end

  test "transfer amount must be a positive decimal string within BIGINT range" do
    transfer = %{
      @base
      | "operation" => "wallet.transfer",
        "args" => %{"recipient_user_id" => "456789012345678901", "amount" => "50"}
    }

    assert {:ok, _} = Contract.validate(transfer)
    assert {:error, "INVALID_AMOUNT"} = Contract.validate(put_in(transfer, ["args", "amount"], 50))

    assert {:error, "INVALID_AMOUNT"} =
             Contract.validate(put_in(transfer, ["args", "amount"], "9223372036854775808"))
    assert {:error, "INVALID_TARGET"} =
             Contract.validate(put_in(transfer, ["args", "recipient_user_id"], "not-a-snowflake"))
  end

  test "read-only balance does not require idempotency key" do
    balance = @base |> Map.put("operation", "wallet.balance") |> Map.delete("idempotency_key")
    assert {:ok, _} = Contract.validate(balance)
  end

  test "inventory, catalog and leaderboard reads validate bounded arguments without idempotency keys" do
    for {operation, args} <- [
          {"inventory.list", %{}},
          {"inventory.item", %{"item_id" => "leopard_baby_tee"}},
          {"shop.catalog", %{"category" => "tools"}},
          {"wallet.leaderboard", %{"limit" => 100}}
        ] do
      request = @base |> Map.put("operation", operation) |> Map.put("args", args) |> Map.delete("idempotency_key")
      assert {:ok, _} = Contract.validate(request)
    end

    invalid_limit = @base |> Map.put("operation", "wallet.leaderboard") |> Map.put("args", %{"limit" => 101}) |> Map.delete("idempotency_key")
    invalid_category = @base |> Map.put("operation", "shop.catalog") |> Map.put("args", %{"category" => "Tools/All"}) |> Map.delete("idempotency_key")
    assert {:error, "INVALID_INPUT"} = Contract.validate(invalid_limit)
    assert {:error, "INVALID_INPUT"} = Contract.validate(invalid_category)
  end

  test "inventory and wardrobe lists validate category and page" do
    for operation <- ["inventory.list", "wardrobe.list"] do
      request =
        @base
        |> Map.put("operation", operation)
        |> Map.put("idempotency_key", nil)
        |> Map.put("args", %{"category" => "dresses", "page" => 1000})

      assert {:ok, %{operation: ^operation}} = Contract.validate(request)
      assert {:error, "INVALID_INPUT"} =
               Contract.validate(put_in(request, ["args", "category"], "bad/category"))

      assert {:error, "INVALID_INPUT"} =
               Contract.validate(put_in(request, ["args", "page"], 1001))
    end
  end

  test "marketplace listing inspection is a read and validates only a stable listing ID" do
    inspect = @base |> Map.put("operation", "marketplace.inspect")
      |> Map.put("args", %{"listing_id" => "listing_123"}) |> Map.delete("idempotency_key")
    assert {:ok, %{operation: "marketplace.inspect"}} = Contract.validate(inspect)
    assert {:error, "INVALID_INPUT"} = Contract.validate(put_in(inspect, ["args", "listing_id"], "../private"))
  end
end
