defmodule ToriEconomy.Consumables do
  @moduledoc "Configured item use and persistent effects over the existing inventory."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}

  def execute(%{operation: "inventory.effects"} = request) do
    user = request.context["target_user_id"] || request.context["actor_user_id"]
    effects = Sql.query!("""
      SELECT effect_code,source_item_id,floor(extract(epoch from expires_at)*1000)::bigint
      FROM economy_v2_active_effects WHERE user_id=$1 AND expires_at>now() ORDER BY effect_code
    """, [user]).rows
    |> Enum.map(fn [code, item, expires] ->
      %{"effect_code" => code, "source_item_id" => item,
        "expires_at_ms" => Integer.to_string(expires)}
    end)
    {:ok, %{"request_id" => request.request_id, "status" => "ok",
            "result" => %{"type" => "active_effects", "user_id" => user, "effects" => effects}}}
  end

  def execute(%{operation: "inventory.consume"} = request) do
    Idempotency.run(request, fn -> consume(request) end,
      skip_persist_errors: ["ITEM_NOT_FOUND", "ITEM_NOT_OWNED", "REQUIREMENT_NOT_MET"])
  end

  defp consume(request) do
    user = request.context["actor_user_id"]
    item_id = request.args["item_id"]
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])
    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         true <- item.consumable || {:error, "REQUIREMENT_NOT_MET"},
         {:ok, effect, duration} <- configured_effect(item_id),
         {:ok, _} <- Inventory.take(user, item_id, 1, request, "consume", "CONSUMABLE_USED") do
      [[expires]] = Sql.query!("""
        INSERT INTO economy_v2_active_effects
          (user_id,effect_code,source_item_id,activated_at,expires_at,request_key)
        VALUES ($1,$2,$3,now(),now()+($4::bigint * interval '1 millisecond'),$5)
        ON CONFLICT (user_id,effect_code) DO UPDATE SET
          source_item_id=EXCLUDED.source_item_id,
          activated_at=EXCLUDED.activated_at,
          expires_at=EXCLUDED.expires_at,
          request_key=EXCLUDED.request_key
        RETURNING floor(extract(epoch from expires_at)*1000)::bigint
      """, [user, effect, item_id, duration, request.idempotency_key]).rows
      %{"status" => "ok", "result" => %{"type" => "consumable_used", "item_id" => item_id,
        "item_name" => item.name, "effect_code" => effect,
        "expires_at_ms" => Integer.to_string(expires),
        "presentation_key" => "consumable.use.success"}}
    else
      {:ok, _} -> error("REQUIREMENT_NOT_MET")
      {:error, code} -> error(code)
    end
  end

  defp configured_effect(item_id) do
    case Sql.query!("SELECT effect_code,duration_ms FROM economy_v2_consumable_effects WHERE item_id=$1 AND active FOR SHARE", [item_id]).rows do
      [[effect, duration]] -> {:ok, effect, duration}
      [] -> {:error, "REQUIREMENT_NOT_MET"}
    end
  end

  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
