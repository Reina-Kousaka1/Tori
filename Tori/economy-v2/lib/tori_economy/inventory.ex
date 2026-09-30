defmodule ToriEconomy.Inventory do
  @moduledoc "Inventory mutations over the existing economy_inventory table. Call only inside a transaction."
  alias ToriEconomy.Sql

  def quantity(user_id, item_id) do
    case Sql.query!(
           "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id=$2 FOR UPDATE",
           [user_id, item_id]
         ).rows do
      [[value]] -> value
      [] -> 0
    end
  end

  def grant(user_id, item, amount, request, leg, reason) do
    current = quantity(user_id, item.id)
    maximum = item.max_stack || 9_223_372_036_854_775_807

    cond do
      amount < 1 ->
        {:error, "INVALID_QUANTITY"}

      not item.stackable and current > 0 ->
        {:error, "ALREADY_OWNED"}

      current > maximum - amount ->
        {:error, "MAX_STACK_REACHED"}

      true ->
        next = current + amount

        Sql.query!(
          """
          INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,$2,$3)
          ON CONFLICT (user_id,item_id) DO UPDATE SET quantity=EXCLUDED.quantity
          """,
          [user_id, item.id, next]
        )

        event(user_id, item.id, request, leg, amount, next, reason)
        {:ok, next}
    end
  end

  def take(user_id, item_id, amount, request, leg, reason) do
    current = quantity(user_id, item_id)

    if amount < 1 or current < amount do
      {:error, "ITEM_NOT_OWNED"}
    else
      next = current - amount

      if next == 0 do
        Sql.query!("DELETE FROM economy_inventory WHERE user_id=$1 AND item_id=$2", [
          user_id,
          item_id
        ])

        Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND item_id=$2", [
          user_id,
          item_id
        ])
      else
        Sql.query!(
          "UPDATE economy_inventory SET quantity=$3 WHERE user_id=$1 AND item_id=$2",
          [user_id, item_id, next]
        )
      end

      event(user_id, item_id, request, leg, -amount, next, reason)
      {:ok, next}
    end
  end

  # Return of an escrowed item must never be lost because the seller acquired
  # another copy while the listing was live. Purchase stack rules do not apply.
  def restore_escrow(user_id, item_id, amount, request) do
    current = quantity(user_id, item_id)
    next = current + amount

    Sql.query!(
      """
      INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,$2,$3)
      ON CONFLICT (user_id,item_id) DO UPDATE SET quantity=EXCLUDED.quantity
      """,
      [user_id, item_id, next]
    )

    event(user_id, item_id, request, "listing_return", amount, next, "MARKETPLACE_RETURN")
    {:ok, next}
  end

  defp event(user_id, item_id, request, leg, delta, after_quantity, reason) do
    Sql.query!(
      """
      INSERT INTO economy_v2_inventory_events
        (user_id,item_id,request_key,leg,delta,quantity_after,reason_code)
      VALUES ($1,$2,$3,$4,$5,$6,$7)
      """,
      [user_id, item_id, request.idempotency_key, leg, delta, after_quantity, reason]
    )
  end
end
