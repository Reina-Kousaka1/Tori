defmodule ToriEconomy.Catalog do
  @moduledoc "Original V2 catalog; legacy dynamic-market rows remain Java-owned."
  alias ToriEconomy.Sql

  def list_active do
    Sql.query!(
      """
      SELECT item_id,name,description,category,subcategory,rarity,buy_price,sell_price,
             rotation_weight,season,stackable,max_stack,consumable,tradeable,
             equip_slots,conflict_slots,tags,level_requirement,metadata,active,cosmetic_slots,
             career_requirement,career_level_requirement
      FROM economy_v2_catalog_items WHERE active AND buy_price IS NOT NULL ORDER BY item_id
      """
    ).rows
    |> Enum.map(&row/1)
  end

  def fetch_for_update(item_id) do
    case Sql.query!(
           """
           SELECT item_id,name,description,category,subcategory,rarity,buy_price,sell_price,
                  rotation_weight,season,stackable,max_stack,consumable,tradeable,
                  equip_slots,conflict_slots,tags,level_requirement,metadata,active,cosmetic_slots,
                  career_requirement,career_level_requirement
           FROM economy_v2_catalog_items WHERE item_id=$1 FOR SHARE
           """,
           [item_id]
         ).rows do
      [entry] -> {:ok, row(entry)}
      [] -> {:error, "ITEM_NOT_FOUND"}
    end
  end

  defp row([id, name, description, category, subcategory, rarity, buy_price, sell_price,
            weight, season, stackable, max_stack, consumable, tradeable, equip_slots,
            conflict_slots, tags, level_requirement, metadata, active, cosmetic_slots,
            career_requirement, career_level_requirement]) do
    %{id: id, name: name, description: description, category: category,
      subcategory: subcategory, rarity: rarity, buy_price: buy_price,
      sell_price: sell_price, weight: weight, season: season,
      stackable: stackable, max_stack: max_stack, consumable: consumable,
      tradeable: tradeable, equip_slots: equip_slots, conflict_slots: conflict_slots,
      tags: tags, level_requirement: level_requirement, metadata: metadata, active: active,
      cosmetic_slots: cosmetic_slots, career_requirement: career_requirement,
      career_level_requirement: career_level_requirement}
  end
end
