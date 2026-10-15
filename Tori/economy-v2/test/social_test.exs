defmodule ToriEconomy.SocialTest do
  use ExUnit.Case, async: false

  alias ToriEconomy.{Contract, Repo, Social, Sql, TestSchema}

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @moduletag skip: if(is_nil(@url), do: "requires explicit isolated *_test database", else: false)

  setup_all do
    TestSchema.ensure_target!(@url)
    :ok
  end

  defp snowflake,
    do: Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))

  defp request(actor, target, action, interaction \\ nil) do
    raw = %{
      "request_id" => Ecto.UUID.generate(),
      "idempotency_key" => "discord-interaction:" <> (interaction || snowflake()),
      "operation" => "social.action",
      "context" => %{
        "actor_user_id" => actor,
        "target_user_id" => target,
        "guild_id" => "234567890123456789",
        "channel_id" => "345678901234567890"
      },
      "args" => %{"action_id" => action}
    }

    {:ok, validated} = Contract.validate(raw)
    validated
  end

  test "successful action persists one pair cooldown and exact interaction replay" do
    actor = snowflake()
    target = snowflake()
    interaction = snowflake()
    action = request(actor, target, "hug", interaction)

    assert {:ok, first} = Social.execute(action)
    assert first["result"]["action_id"] == "hug"
    assert first["result"]["actor_user_id"] == actor
    assert first["result"]["target_user_id"] == target
    assert first["result"]["social_effect"] == "none"
    assert first["result"]["cooldown_ms"] == "86400000"

    assert {:ok, replay} = Social.execute(%{action | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_social_action_cooldowns WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code='hug'",
               [actor, target]
             ).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_requests WHERE idempotency_key=$1",
               [action.idempotency_key]
             ).rows
  end

  test "cooldown blocks a repeat under 24 hours but allows it at the boundary" do
    actor = snowflake()
    target = snowflake()
    assert {:ok, _} = Social.execute(request(actor, target, "wave"))
    duration = ToriEconomy.Social.ActionRegistry.cooldown_ms()

    Sql.query!(
      "UPDATE economy_v2_social_action_cooldowns SET last_action_at_ms=floor(extract(epoch from clock_timestamp())*1000)::bigint-($4::bigint-60000) WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code=$3",
      [actor, target, "wave", duration]
    )

    assert {:ok, blocked} = Social.execute(request(actor, target, "wave"))
    assert blocked["error"]["code"] == "COOLDOWN_ACTIVE"
    assert blocked["error"]["details"]["retry_after_ms"] > 0
    assert blocked["error"]["details"]["retry_after_ms"] < duration

    Sql.query!(
      "UPDATE economy_v2_social_action_cooldowns SET last_action_at_ms=floor(extract(epoch from clock_timestamp())*1000)::bigint-$4::bigint WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code=$3",
      [actor, target, "wave", duration]
    )

    assert {:ok, allowed} = Social.execute(request(actor, target, "wave"))
    assert allowed["status"] == "ok"
  end

  test "parallel distinct interactions serialize one success against one cooldown" do
    actor = snowflake()
    target = snowflake()

    results =
      [request(actor, target, "highfive"), request(actor, target, "highfive")]
      |> Enum.map(&Task.async(fn -> Social.execute(&1) end))
      |> Enum.map(&Task.await(&1, 15_000))

    assert Enum.count(results, fn {:ok, result} -> result["status"] == "ok" end) == 1
    assert Enum.count(results, fn {:ok, result} -> result["error"]["code"] == "COOLDOWN_ACTIVE" end) == 1

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_social_action_cooldowns WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code='highfive'",
               [actor, target]
             ).rows
  end

  test "parallel delivery of the same interaction returns one persisted result" do
    actor = snowflake()
    target = snowflake()
    action = request(actor, target, "kiss", snowflake())

    results =
      1..2
      |> Enum.map(fn _ -> Task.async(fn -> Social.execute(action) end) end)
      |> Enum.map(&Task.await(&1, 15_000))

    assert [{:ok, first}, {:ok, second}] = results
    assert first["result"] == second["result"]

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_social_action_cooldowns WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code='kiss'",
               [actor, target]
             ).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_requests WHERE idempotency_key=$1",
               [action.idempotency_key]
             ).rows
  end

  test "persisted interaction replay survives a supervised Repo restart" do
    actor = snowflake()
    target = snowflake()
    action = request(actor, target, "boop")
    assert {:ok, first} = Social.execute(action)

    assert :ok = Supervisor.terminate_child(ToriEconomy.Supervisor, Repo)
    assert {:ok, new_repo} = Supervisor.restart_child(ToriEconomy.Supervisor, Repo)
    assert is_pid(new_repo)

    assert {:ok, replay} = Social.execute(%{action | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert {:ok, blocked} = Social.execute(request(actor, target, "boop"))
    assert blocked["error"]["code"] == "COOLDOWN_ACTIVE"
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_social_action_cooldowns WHERE actor_user_id=$1", [actor]).rows
  end
end
