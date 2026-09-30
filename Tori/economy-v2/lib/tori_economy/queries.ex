defmodule ToriEconomy.Queries do
  @moduledoc "Read-only queries over Tori's existing economy tables."
  alias ToriEconomy.Sql

  def execute(%{operation: "profile.snapshot"} = request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]

    balance =
      case Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [user_id]).rows do
        [[value]] -> value
        [] -> 0
      end

    items =
      Sql.query!(
        """
        SELECT i.item_id,i.quantity,c.name,c.category,c.subcategory,c.rarity,
               c.equip_slots,c.cosmetic_slots,
               EXISTS(SELECT 1 FROM economy_v2_loadout l WHERE l.user_id=i.user_id AND l.item_id=i.item_id) AS equipped,
               EXISTS(SELECT 1 FROM economy_v2_cosmetic_selections s WHERE s.user_id=i.user_id AND s.item_id=i.item_id) AS selected_cosmetic
        FROM economy_inventory i LEFT JOIN economy_v2_catalog_items c ON c.item_id=i.item_id
        WHERE i.user_id=$1 AND i.quantity>0 ORDER BY i.item_id
        """,
        [user_id]
      ).rows
      |> Enum.map(fn [
                       item_id,
                       quantity,
                       name,
                       category,
                       subcategory,
                       rarity,
                       equip_slots,
                       cosmetic_slots,
                       equipped,
                       selected_cosmetic
                     ] ->
        %{
          "item_id" => item_id,
          "name" => name || item_id,
          "quantity" => Integer.to_string(quantity),
          "category" => category,
          "subcategory" => subcategory,
          "rarity" => rarity,
          "equip_slots" => equip_slots || [],
          "cosmetic_slots" => cosmetic_slots || [],
          "equipped" => equipped,
          "selected_cosmetic" => selected_cosmetic
        }
      end)

    equipment =
      Sql.query!("SELECT slot, item_id FROM economy_equipment WHERE user_id=$1 ORDER BY slot", [
        user_id
      ]).rows
      |> Enum.map(fn [slot, item_id] -> %{"slot" => slot, "item_id" => item_id} end)

    loadout =
      Sql.query!(
        """
          SELECT l.slot,l.item_id,c.name,c.category,c.rarity FROM economy_v2_loadout l
          LEFT JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
          WHERE l.user_id=$1 ORDER BY l.slot
        """,
        [user_id]
      ).rows
      |> Enum.map(fn [slot, item_id, name, category, rarity] ->
        %{
          "slot" => slot,
          "item_id" => item_id,
          "name" => name || item_id,
          "category" => category,
          "rarity" => rarity
        }
      end)

    progression = progression_snapshot(user_id)
    cosmetics = cosmetic_snapshot(user_id)

    activities =
      Sql.query!(
        """
        SELECT 'fish',last_fish_at FROM economy_accounts WHERE user_id=$1
        UNION ALL SELECT 'mine',last_mine_at FROM economy_accounts WHERE user_id=$1
        UNION ALL SELECT 'chop',last_chop_at FROM economy_accounts WHERE user_id=$1
        """,
        [user_id]
      ).rows
      |> Enum.map(fn [activity, last_at] ->
        %{"activity" => activity, "last_at_ms" => Integer.to_string(last_at)}
      end)

    ok(request, %{
      "type" => "profile_snapshot",
      "user_id" => user_id,
      "balance" => Integer.to_string(balance),
      "items" => items,
      "equipment" => equipment,
      "loadout" => loadout,
      "cosmetics" => cosmetics,
      "activities" => activities,
      "progression" => progression
    })
  end

  def execute(%{operation: operation} = request)
      when operation in ["inventory.list", "wardrobe.list"] do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]
    category = Map.get(request.args, "category", "all")
    wardrobe_only = operation == "wardrobe.list"

    [[total]] =
      Sql.query!(
        """
          SELECT count(*) FROM economy_inventory i LEFT JOIN economy_v2_catalog_items c ON c.item_id=i.item_id
          WHERE i.user_id=$1 AND i.quantity>0 AND ($2='all' OR c.category=$2)
            AND (NOT $3 OR coalesce(cardinality(c.equip_slots),0)>0 OR coalesce(cardinality(c.cosmetic_slots),0)>0)
        """,
        [user_id, category, wardrobe_only]
      ).rows

    total_pages = max(1, div(total + 19, 20))
    page = min(Map.get(request.args, "page", 0), total_pages - 1)

    items =
      Sql.query!(
        """
        SELECT i.item_id,i.quantity,c.name,c.category,c.subcategory,c.rarity,
               c.equip_slots,c.cosmetic_slots,
               EXISTS(SELECT 1 FROM economy_v2_loadout l WHERE l.user_id=i.user_id AND l.item_id=i.item_id),
               EXISTS(SELECT 1 FROM economy_v2_cosmetic_selections s WHERE s.user_id=i.user_id AND s.item_id=i.item_id)
        FROM economy_inventory i LEFT JOIN economy_v2_catalog_items c ON c.item_id=i.item_id
        WHERE i.user_id=$1 AND i.quantity>0 AND ($2='all' OR c.category=$2)
          AND (NOT $4 OR coalesce(cardinality(c.equip_slots),0)>0 OR coalesce(cardinality(c.cosmetic_slots),0)>0)
        ORDER BY i.item_id LIMIT 20 OFFSET $3
        """,
        [user_id, category, page * 20, wardrobe_only]
      ).rows
      |> Enum.map(fn [
                       item_id,
                       quantity,
                       name,
                       category,
                       subcategory,
                       rarity,
                       equip_slots,
                       cosmetic_slots,
                       equipped,
                       selected_cosmetic
                     ] ->
        %{
          "item_id" => item_id,
          "name" => name || item_id,
          "quantity" => Integer.to_string(quantity),
          "category" => category,
          "subcategory" => subcategory,
          "rarity" => rarity,
          "equip_slots" => equip_slots || [],
          "cosmetic_slots" => cosmetic_slots || [],
          "equipped" => equipped,
          "selected_cosmetic" => selected_cosmetic
        }
      end)

    ok(request, %{
      "type" => if(wardrobe_only, do: "wardrobe", else: "inventory"),
      "user_id" => user_id,
      "items" => items,
      "category" => category,
      "page" => page,
      "page_size" => 20,
      "total_items" => total,
      "total_pages" => total_pages
    })
  end

  def execute(%{operation: "inventory.item"} = request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]
    item_id = request.args["item_id"]

    case Sql.query!(
           """
             SELECT i.quantity,c.name,c.description,c.category,c.subcategory,c.rarity,c.tags,
                    c.equip_slots,c.conflict_slots,c.cosmetic_slots,c.level_requirement,
                    c.career_requirement,c.career_level_requirement,c.consumable,c.tradeable,
                    ce.effect_code,ce.duration_ms,ce.active,
                    EXISTS(SELECT 1 FROM economy_v2_loadout l WHERE l.user_id=i.user_id AND l.item_id=i.item_id),
                    EXISTS(SELECT 1 FROM economy_v2_cosmetic_selections s WHERE s.user_id=i.user_id AND s.item_id=i.item_id)
             FROM economy_inventory i LEFT JOIN economy_v2_catalog_items c ON c.item_id=i.item_id
             LEFT JOIN economy_v2_consumable_effects ce ON ce.item_id=i.item_id
             WHERE i.user_id=$1 AND i.item_id=$2 AND i.quantity>0
           """,
           [user_id, item_id]
         ).rows do
      [
        [
          quantity,
          name,
          description,
          category,
          subcategory,
          rarity,
          tags,
          equip_slots,
          conflict_slots,
          cosmetic_slots,
          level_requirement,
          career_requirement,
          career_level_requirement,
          consumable,
          tradeable,
          effect_code,
          effect_duration,
          effect_active,
          equipped,
          selected_cosmetic
        ]
      ] ->
        ok(request, %{
          "type" => "inventory_item",
          "user_id" => user_id,
          "item_id" => item_id,
          "name" => name || item_id,
          "description" => description || "",
          "quantity" => Integer.to_string(quantity),
          "category" => category,
          "subcategory" => subcategory,
          "rarity" => rarity,
          "tags" => tags || [],
          "equip_slots" => equip_slots || [],
          "conflict_slots" => conflict_slots || [],
          "cosmetic_slots" => cosmetic_slots || [],
          "level_requirement" => level_requirement,
          "career_requirement" => career_requirement,
          "career_level_requirement" => career_level_requirement,
          "consumable" => consumable || false,
          "tradeable" => tradeable || false,
          "effect_code" => effect_code,
          "effect_duration_ms" => effect_duration,
          "effect_active" => effect_active || false,
          "equipped" => equipped,
          "selected_cosmetic" => selected_cosmetic
        })

      [] ->
        {:ok,
         %{
           "request_id" => request.request_id,
           "status" => "error",
           "error" => %{"code" => "ITEM_NOT_OWNED", "retryable" => false}
         }}
    end
  end

  def execute(%{operation: "inventory.cosmetics"} = request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]

    ok(request, %{
      "type" => "cosmetics",
      "user_id" => user_id,
      "cosmetics" => cosmetic_snapshot(user_id)
    })
  end

  def execute(%{operation: "wallet.leaderboard"} = request) do
    limit = Map.get(request.args, "limit", 10)

    entries =
      Sql.query!(
        "SELECT user_id, balance FROM economy_accounts ORDER BY balance DESC, user_id LIMIT $1",
        [limit]
      ).rows
      |> Enum.map(fn [user_id, balance] ->
        %{"user_id" => user_id, "balance" => Integer.to_string(balance)}
      end)

    ok(request, %{"type" => "leaderboard", "entries" => entries})
  end

  def execute(%{operation: "shop.catalog"} = request) do
    category = Map.get(request.args, "category", "all")

    products =
      Sql.query!(
        """
        SELECT p.product_id, p.name, p.description, p.category, p.current_price, p.stock,
               p.available, p.rarity,
               (SELECT s.discount_percent FROM economy_market_sales s
                WHERE s.status='ACTIVE' AND s.starts_at<=now() AND s.ends_at>now()
                  AND (s.product_id=p.product_id OR s.category=p.category)
                ORDER BY s.starts_at DESC LIMIT 1) AS discount_percent
        FROM economy_market_products p
        WHERE ($1='all' OR ($1='utility' AND p.category IN ('common','tools')) OR p.category=$1)
        ORDER BY p.product_id
        """,
        [category]
      ).rows
      |> Enum.map(fn [
                       id,
                       name,
                       description,
                       category,
                       current_price,
                       stock,
                       available,
                       rarity,
                       discount
                     ] ->
        effective_price =
          if discount == nil,
            do: current_price,
            else: max(1, round(current_price * (100 - discount) / 100))

        %{
          "product_id" => id,
          "name" => name,
          "description" => description,
          "category" => category,
          "current_price" => Integer.to_string(current_price),
          "effective_price" => Integer.to_string(effective_price),
          "stock" => Integer.to_string(stock),
          "available" => available,
          "rarity" => rarity
        }
      end)

    sales =
      Sql.query!("""
      SELECT product_id, category, discount_percent, floor(extract(epoch from ends_at))::bigint
      FROM economy_market_sales
      WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now()
      ORDER BY starts_at DESC
      """).rows
      |> Enum.map(fn [product_id, category, discount, ends_at] ->
        %{
          "product_id" => product_id,
          "category" => category,
          "discount_percent" => discount,
          "ends_at_epoch" => Integer.to_string(ends_at)
        }
      end)

    ok(request, %{"type" => "shop_catalog", "products" => products, "sales" => sales})
  end

  defp ok(request, result) do
    {:ok, %{"request_id" => request.request_id, "status" => "ok", "result" => result}}
  end

  defp progression_snapshot(user_id) do
    xp =
      case Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user_id]).rows do
        [[value]] -> value
        [] -> 0
      end

    [[level, start_xp, next_xp]] =
      Sql.query!(
        """
          SELECT current.level,current.required_xp,next.required_xp
          FROM economy_v2_xp_thresholds current
          LEFT JOIN economy_v2_xp_thresholds next ON next.level=current.level+1
          WHERE current.required_xp<=$1 ORDER BY current.level DESC LIMIT 1
        """,
        [xp]
      ).rows

    active_career =
      case Sql.query!(
             """
               SELECT s.career_code,c.display_name,coalesce(p.xp,0)
               FROM economy_v2_career_selections s
               JOIN economy_v2_careers c ON c.career_code=s.career_code
               LEFT JOIN economy_v2_career_progress p ON p.user_id=s.user_id AND p.career_code=s.career_code
               WHERE s.user_id=$1
             """,
             [user_id]
           ).rows do
        [[code, name, career_xp]] ->
          [[career_level]] =
            Sql.query!("SELECT max(level) FROM economy_v2_xp_thresholds WHERE required_xp<=$1", [
              career_xp
            ]).rows

          %{
            "code" => code,
            "name" => name,
            "xp" => Integer.to_string(career_xp),
            "level" => career_level || 1
          }

        [] ->
          nil
      end

    %{
      "xp" => Integer.to_string(xp),
      "level" => level || 1,
      "level_start_xp" => Integer.to_string(start_xp || 0),
      "next_level_xp" => if(next_xp, do: Integer.to_string(next_xp)),
      "active_career" => active_career
    }
  end

  defp cosmetic_snapshot(user_id) do
    Sql.query!(
      """
        SELECT s.slot,s.item_id,c.name,c.category,c.rarity
        FROM economy_v2_cosmetic_selections s
        JOIN economy_v2_catalog_items c ON c.item_id=s.item_id
        WHERE s.user_id=$1 ORDER BY s.slot
      """,
      [user_id]
    ).rows
    |> Enum.map(fn [slot, item_id, name, category, rarity] ->
      %{
        "slot" => slot,
        "item_id" => item_id,
        "name" => name,
        "category" => category,
        "rarity" => rarity
      }
    end)
  end
end
