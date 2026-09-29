defmodule ToriEconomy.Shop do
  @moduledoc "A gated, idempotent purchase path using the global wallet and inventory."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}
  alias ToriEconomy.Shop.Rotation

  def execute(%{operation: "shop.rotation"} = request) do
    case Rotation.current() do
      {:ok, result} -> {:ok, %{"request_id" => request.request_id, "status" => "ok", "result" => result}}
      error -> error
    end
  end

  def execute(%{operation: "shop.purchase"} = request) do
    Idempotency.run(request, fn -> purchase(request) end,
      skip_persist_errors: ["ITEM_NOT_FOUND", "ITEM_NOT_AVAILABLE", "INSUFFICIENT_FUNDS",
                            "INVALID_QUANTITY", "MAX_STACK_REACHED", "ALREADY_OWNED",
                            "REQUIREMENT_NOT_MET"])
  end

  defp purchase(request) do
    user_id = request.context["actor_user_id"]
    item_id = request.args["item_id"]
    amount = request.args["quantity"]
    period_key = String.to_integer(request.args["period_key"])

    # Serialize all wallet/inventory mutations for this user, including other V2 operations.
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user_id])
    [[balance]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user_id]).rows

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         {:ok, unit_price} <- available_item(period_key, item_id, amount),
         :ok <- requirements(user_id, item, amount),
         total = unit_price * amount,
         true <- balance >= total || {:error, "INSUFFICIENT_FUNDS"},
         {:ok, after_quantity} <- Inventory.grant(user_id, item, amount, request, "shop_grant", "SHOP_PURCHASE") do
      remaining = balance - total
      Sql.query!("UPDATE economy_accounts SET balance=$2,updated_at=now() WHERE user_id=$1", [user_id, remaining])
      Sql.query!("""
      INSERT INTO economy_v2_ledger_entries
        (user_id,request_key,leg,delta,balance_after,reason_code,guild_id)
      VALUES ($1,$2,'shop_debit',$3,$4,'SHOP_PURCHASE',$5)
      """, [user_id, request.idempotency_key, -total, remaining, request.context["guild_id"]])
      Sql.query!("UPDATE economy_v2_shop_rotation_items SET sold=sold+$3 WHERE period_key=$1 AND item_id=$2",
        [period_key, item_id, amount])
      ok(%{"type" => "shop_purchase", "item_id" => item_id, "item_name" => item.name, "quantity" => amount,
           "quantity_after" => Integer.to_string(after_quantity), "spent" => Integer.to_string(total),
           "balance" => Integer.to_string(remaining), "presentation_key" => "shop.purchase.success"})
    else
      {:error, code} -> error(code)
    end
  end

  defp available_item(period_key, item_id, amount) do
    case Sql.query!("""
         SELECT r.unit_price,r.stock_limit,r.sold
         FROM economy_v2_shop_rotation_items r
         JOIN economy_v2_shop_rotations s ON s.period_key=r.period_key
         WHERE r.period_key=$1 AND r.item_id=$2 AND s.starts_at<=now() AND s.ends_at>now()
         FOR UPDATE OF r
         """, [period_key, item_id]).rows do
      [[price, limit, sold]] when is_nil(limit) or sold <= limit - amount -> {:ok, price}
      _ -> {:error, "ITEM_NOT_AVAILABLE"}
    end
  end

  defp requirements(user_id, item, amount) do
    current = Inventory.quantity(user_id, item.id)
    level = case Sql.query!("""
      SELECT coalesce(max(level),1) FROM economy_v2_xp_thresholds
      WHERE required_xp <= coalesce((SELECT xp FROM economy_v2_account_progress WHERE user_id=$1),0)
      """, [user_id]).rows do [[value]] -> value end
    cond do
      not item.active -> {:error, "ITEM_NOT_AVAILABLE"}
      amount < 1 or amount > 100 -> {:error, "INVALID_QUANTITY"}
      current > (item.max_stack || 9_223_372_036_854_775_807) - amount -> {:error, "MAX_STACK_REACHED"}
      not item.stackable and current > 0 -> {:error, "ALREADY_OWNED"}
      level < item.level_requirement -> {:error, "REQUIREMENT_NOT_MET"}
      true -> :ok
    end
  end

  defp ok(result), do: %{"status" => "ok", "result" => result}
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
