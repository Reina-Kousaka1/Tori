defmodule ToriEconomy.Equipment do
  @moduledoc "Fashion loadout using owned items in the existing inventory."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}

  def execute(request), do: Idempotency.run(request, fn -> change(request) end,
    skip_persist_errors: ["ITEM_NOT_FOUND", "ITEM_NOT_OWNED", "INVALID_INPUT"])

  defp change(%{operation: "inventory.unequip"} = request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND slot=$2", [user, slot])
    ok(%{"type" => "loadout", "slot" => slot, "item_id" => nil})
  end

  defp change(request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    item_id = request.args["item_id"]
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         true <- slot in item.equip_slots || {:error, "INVALID_INPUT"},
         true <- Inventory.quantity(user, item_id) > 0 || {:error, "ITEM_NOT_OWNED"} do
      # A dress occupies top and bottom visually; conflicting items are removed together.
      slots = Enum.uniq([slot | item.conflict_slots])
      Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND slot=ANY($2::text[])", [user, slots])
      Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND item_id=$2", [user, item_id])
      Sql.query!("""
      DELETE FROM economy_v2_loadout l USING economy_v2_catalog_items c
      WHERE l.user_id=$1 AND l.item_id=c.item_id AND $2=ANY(c.conflict_slots)
      """, [user, slot])
      Sql.query!("INSERT INTO economy_v2_loadout(user_id,slot,item_id) VALUES ($1,$2,$3)", [user, slot, item_id])
      ok(%{"type" => "loadout", "slot" => slot, "item_id" => item_id})
    else
      {:error, code} -> error(code)
    end
  end

  defp ok(result), do: %{"status" => "ok", "result" => result}
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
