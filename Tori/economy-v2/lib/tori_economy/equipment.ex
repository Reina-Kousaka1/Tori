defmodule ToriEconomy.Equipment do
  @moduledoc "Fashion loadout and permanent cosmetics using the existing inventory."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}

  def execute(request), do: Idempotency.run(request, fn -> change(request) end,
    skip_persist_errors: ["ITEM_NOT_FOUND", "ITEM_NOT_OWNED", "INVALID_INPUT",
                          "REQUIREMENT_NOT_MET"])

  defp change(%{operation: "inventory.unequip"} = request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND slot=$2", [user, slot])
    ok(%{"type" => "loadout", "slot" => slot, "item_id" => nil})
  end

  defp change(%{operation: "inventory.cosmetic.clear"} = request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    Sql.query!("DELETE FROM economy_v2_cosmetic_selections WHERE user_id=$1 AND slot=$2", [user, slot])
    ok(%{"type" => "cosmetic", "slot" => slot, "item_id" => nil})
  end

  defp change(%{operation: "inventory.cosmetic.select"} = request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    item_id = request.args["item_id"]
    lock_account(user)

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         true <- slot in item.cosmetic_slots || {:error, "REQUIREMENT_NOT_MET"},
         true <- Inventory.quantity(user, item_id) > 0 || {:error, "ITEM_NOT_OWNED"},
         :ok <- unlocked?(user, item) do
      Sql.query!("""
        INSERT INTO economy_v2_cosmetic_selections(user_id,slot,item_id)
        VALUES ($1,$2,$3)
        ON CONFLICT (user_id,slot) DO UPDATE SET item_id=EXCLUDED.item_id,selected_at=now()
      """, [user, slot, item_id])
      ok(%{"type" => "cosmetic", "slot" => slot, "item_id" => item_id,
           "presentation_key" => "wardrobe.cosmetic.success"})
    else
      {:error, code} -> error(code)
      false -> error("REQUIREMENT_NOT_MET")
    end
  end

  defp change(request) do
    user = request.context["actor_user_id"]
    slot = request.args["slot"]
    item_id = request.args["item_id"]
    lock_account(user)

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         true <- slot in item.equip_slots || {:error, "INVALID_INPUT"},
         true <- Inventory.quantity(user, item_id) > 0 || {:error, "ITEM_NOT_OWNED"},
         :ok <- unlocked?(user, item) do
      slots = Enum.uniq([slot | item.conflict_slots])
      Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND slot=ANY($2::text[])", [user, slots])
      Sql.query!("DELETE FROM economy_v2_loadout WHERE user_id=$1 AND item_id=$2", [user, item_id])
      Sql.query!("""
      DELETE FROM economy_v2_loadout l USING economy_v2_catalog_items c
      WHERE l.user_id=$1 AND l.item_id=c.item_id AND $2=ANY(c.conflict_slots)
      """, [user, slot])
      Sql.query!("INSERT INTO economy_v2_loadout(user_id,slot,item_id) VALUES ($1,$2,$3)", [user, slot, item_id])
      ok(%{"type" => "loadout", "slot" => slot, "item_id" => item_id,
           "presentation_key" => "wardrobe.equip.success"})
    else
      {:error, code} -> error(code)
      false -> error("REQUIREMENT_NOT_MET")
    end
  end

  defp lock_account(user) do
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])
  end

  defp unlocked?(user, item) do
    [[level]] = Sql.query!("""
      SELECT coalesce(max(level),1) FROM economy_v2_xp_thresholds
      WHERE required_xp<=coalesce((SELECT xp FROM economy_v2_account_progress WHERE user_id=$1),0)
    """, [user]).rows
    career_level = if item.career_requirement do
      career_level(user, item.career_requirement)
    else
      item.career_level_requirement
    end
    career_ok = is_nil(item.career_requirement) or career_level >= item.career_level_requirement
    if level >= item.level_requirement and career_ok, do: :ok, else: {:error, "REQUIREMENT_NOT_MET"}
  end

  defp career_level(user, required_career) do
    case Sql.query!("""
      SELECT coalesce(max(t.level),1)
      FROM economy_v2_career_progress p
      LEFT JOIN economy_v2_xp_thresholds t ON t.required_xp<=coalesce(p.xp,0)
      WHERE p.user_id=$1 AND p.career_code=$2
    """, [user, required_career]).rows do
      [[level]] -> level
    end
  end

  defp ok(result), do: %{"status" => "ok", "result" => result}
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
