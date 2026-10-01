defmodule ToriEconomy.MarriageTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Contract, Marriage, Queries, Sql, TestSchema, WriteGate}

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @moduletag skip: if(is_nil(@url), do: "requires explicit isolated *_test database", else: false)

  setup_all do
    TestSchema.ensure_target!(@url)
    :ok
  end

  defp snowflake,
    do: Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))

  defp request(operation, actor, args \\ %{}, interaction \\ nil) do
    raw = %{
      "request_id" => Ecto.UUID.generate(),
      "operation" => operation,
      "context" => %{
        "actor_user_id" => actor,
        "guild_id" => "234567890123456789",
        "channel_id" => "345678901234567890"
      },
      "args" => args,
      "idempotency_key" =>
        if(operation == "marriage.snapshot",
          do: nil,
          else: "discord-interaction:" <> (interaction || snowflake())
        )
    }

    {:ok, validated} = Contract.validate(raw)
    validated
  end

  test "proposal, acceptance and divorce are idempotent and profile only reads active state" do
    proposer = snowflake()
    recipient = snowflake()
    proposal = request("marriage.propose", proposer, %{"target_user_id" => recipient})

    assert {:ok, first} = Marriage.execute(proposal)
    assert first["result"]["relationship"]["status"] == "PENDING"

    assert {:ok, replay} =
             Marriage.execute(%{proposal | request_id: Ecto.UUID.generate()})

    assert replay["result"] == first["result"]
    assert {:ok, %{"error" => %{"code" => "NO_PENDING_PROPOSAL"}}} =
             Marriage.execute(request("marriage.accept", proposer))

    assert {:ok, accepted} = Marriage.execute(request("marriage.accept", recipient))
    assert accepted["result"]["relationship"]["status"] == "MARRIED"
    assert accepted["result"]["relationship"]["responded_at"]

    assert {:ok, profile} = Queries.execute(request("profile.snapshot", proposer))
    assert profile["result"]["relationship"]["partner_user_id"] == recipient
    assert profile["result"]["relationship"]["status"] == "MARRIED"

    assert {:ok, divorce} = Marriage.execute(request("marriage.divorce", proposer))
    assert divorce["result"]["relationship"]["status"] == "DIVORCED"
    assert divorce["result"]["relationship"]["ended_at"]

    assert {:ok, profile} = Queries.execute(request("profile.snapshot", recipient))
    assert profile["result"]["relationship"] == nil

    assert {:ok, latest} = Marriage.execute(request("marriage.snapshot", recipient))
    assert latest["result"]["relationship"]["status"] == "DIVORCED"

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_marriages WHERE proposer_id=$1 AND recipient_id=$2",
               [proposer, recipient]
             ).rows
  end

  test "self proposals and competing relationships cannot become active" do
    proposer = snowflake()
    first = snowflake()
    second = snowflake()

    assert {:ok, %{"error" => %{"code" => "SELF_MARRIAGE"}}} =
             Marriage.execute(request("marriage.propose", proposer, %{"target_user_id" => proposer}))

    tasks =
      [first, second]
      |> Enum.map(fn recipient ->
        Task.async(fn ->
          Marriage.execute(
            request("marriage.propose", proposer, %{"target_user_id" => recipient})
          )
        end)
      end)

    codes =
      tasks
      |> Enum.map(&Task.await(&1, 15_000))
      |> Enum.map(fn {:ok, result} -> get_in(result, ["error", "code"]) || result["status"] end)
      |> Enum.sort()

    assert codes == ["RELATIONSHIP_CONFLICT", "ok"]
  end

  test "decline and proposer cancellation close proposals without fake profile state" do
    proposer = snowflake()
    recipient = snowflake()

    assert {:ok, %{"status" => "ok"}} =
             Marriage.execute(request("marriage.propose", proposer, %{"target_user_id" => recipient}))

    assert {:ok, declined} = Marriage.execute(request("marriage.decline", recipient))
    assert declined["result"]["relationship"]["status"] == "CANCELLED"

    assert {:ok, %{"status" => "ok"}} =
             Marriage.execute(request("marriage.propose", proposer, %{"target_user_id" => recipient}))

    assert {:ok, cancelled} = Marriage.execute(request("marriage.cancel", proposer))
    assert cancelled["result"]["relationship"]["status"] == "CANCELLED"
    assert Marriage.current_for(proposer) == nil
  end

  test "disabled economy writes block relationship mutations" do
    previous = System.get_env("TORI_ECONOMY_WRITE_ENABLED")
    on_exit(fn -> restore("TORI_ECONOMY_WRITE_ENABLED", previous) end)
    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "false")
    assert {:error, "READ_ONLY"} = WriteGate.authorize("marriage.propose")
  end

  defp restore(name, nil), do: System.delete_env(name)
  defp restore(name, value), do: System.put_env(name, value)
end
