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
    assert {:error, "INVALID_INPUT"} = Contract.validate(put_in(transfer, ["args", "amount"], 50))

    assert {:error, "INVALID_INPUT"} =
             Contract.validate(put_in(transfer, ["args", "amount"], "9223372036854775808"))
  end

  test "read-only balance does not require idempotency key" do
    balance = @base |> Map.put("operation", "wallet.balance") |> Map.delete("idempotency_key")
    assert {:ok, _} = Contract.validate(balance)
  end
end
