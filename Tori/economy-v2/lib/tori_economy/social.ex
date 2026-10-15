defmodule ToriEconomy.Social do
  @moduledoc "Persistent social-action cooldowns and idempotent domain results."
  alias ToriEconomy.{Idempotency, Sql}
  alias ToriEconomy.Social.ActionRegistry

  def execute(%{operation: "social.action"} = request) do
    Idempotency.run(request, fn -> perform(request) end)
  end

  defp perform(request) do
    action_id = request.args["action_id"]
    actor = request.context["actor_user_id"]
    target = request.context["target_user_id"]

    with {:ok, definition} <- ActionRegistry.fetch(action_id),
         :ok <- ActionRegistry.validate_target(action_id, actor, target) do
      lock_key = Enum.join(["social", actor, target, action_id], ":")
      Sql.query!("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [lock_key])
      [[now_ms]] = Sql.query!("SELECT floor(extract(epoch from clock_timestamp()) * 1000)::bigint").rows

      case cooldown_row(actor, target, action_id) do
        nil ->
          complete(request, definition, actor, target, now_ms)

        last_action_at_ms ->
          remaining = cooldown_remaining_ms(now_ms, last_action_at_ms, definition.cooldown_ms)

          if remaining > 0 do
            error("COOLDOWN_ACTIVE", %{"retry_after_ms" => remaining})
          else
            complete(request, definition, actor, target, now_ms)
          end
      end
    else
      {:error, code} -> error(code)
    end
  end

  defp cooldown_row(actor, target, action_id) do
    case Sql.query!(
           "SELECT last_action_at_ms FROM economy_v2_social_action_cooldowns WHERE actor_user_id=$1 AND target_user_id=$2 AND action_code=$3",
           [actor, target, action_id]
         ).rows do
      [[last_action_at_ms]] -> last_action_at_ms
      [] -> nil
    end
  end

  defp complete(_request, definition, actor, target, now_ms) do
    Sql.query!(
      """
      INSERT INTO economy_v2_social_action_cooldowns
        (actor_user_id,target_user_id,action_code,last_action_at_ms)
      VALUES ($1,$2,$3,$4)
      ON CONFLICT (actor_user_id,target_user_id,action_code)
      DO UPDATE SET last_action_at_ms=EXCLUDED.last_action_at_ms
      """,
      [actor, target, definition.id, now_ms]
    )

    %{
      "status" => "ok",
      "result" => %{
        "type" => definition.result_type,
        "action_id" => definition.id,
        "actor_user_id" => actor,
        "target_user_id" => target,
        "social_effect" => Atom.to_string(definition.social_effect),
        "cooldown_ms" => Integer.to_string(definition.cooldown_ms),
        "cooldown_remaining_ms" => "0"
      }
    }
  end

  @doc false
  def cooldown_remaining_ms(now_ms, last_action_at_ms, cooldown_ms) do
    if now_ms < last_action_at_ms,
      do: cooldown_ms,
      else: max(0, cooldown_ms - (now_ms - last_action_at_ms))
  end

  defp error(code, details \\ %{}) do
    %{
      "status" => "error",
      "error" => %{"code" => code, "retryable" => false, "details" => details}
    }
  end
end
