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
        "SELECT item_id, quantity FROM economy_inventory WHERE user_id=$1 AND quantity>0 ORDER BY item_id",
        [user_id]
      ).rows
      |> Enum.map(fn [item_id, quantity] ->
        %{"item_id" => item_id, "quantity" => Integer.to_string(quantity)}
      end)

    equipment =
      Sql.query!("SELECT slot, item_id FROM economy_equipment WHERE user_id=$1 ORDER BY slot", [user_id]).rows
      |> Enum.map(fn [slot, item_id] -> %{"slot" => slot, "item_id" => item_id} end)

    loadout =
      Sql.query!("SELECT slot,item_id FROM economy_v2_loadout WHERE user_id=$1 ORDER BY slot", [user_id]).rows
      |> Enum.map(fn [slot, item_id] -> %{"slot" => slot, "item_id" => item_id} end)

    activities =
      Sql.query!("""
      SELECT 'fish',last_fish_at FROM economy_accounts WHERE user_id=$1
      UNION ALL SELECT 'mine',last_mine_at FROM economy_accounts WHERE user_id=$1
      UNION ALL SELECT 'chop',last_chop_at FROM economy_accounts WHERE user_id=$1
      """, [user_id]).rows
      |> Enum.map(fn [activity, last_at] -> %{"activity" => activity, "last_at_ms" => Integer.to_string(last_at)} end)

    ok(request, %{
      "type" => "profile_snapshot",
      "user_id" => user_id,
      "balance" => Integer.to_string(balance),
      "items" => items,
      "equipment" => equipment,
      "loadout" => loadout,
      "activities" => activities
    })
  end

  def execute(%{operation: "inventory.list"} = request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]

    items =
      Sql.query!(
        "SELECT item_id, quantity FROM economy_inventory WHERE user_id=$1 AND quantity>0 ORDER BY item_id",
        [user_id]
      ).rows
      |> Enum.map(fn [item_id, quantity] ->
        %{"item_id" => item_id, "quantity" => Integer.to_string(quantity)}
      end)

    ok(request, %{"type" => "inventory", "user_id" => user_id, "items" => items})
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
      |> Enum.map(fn [id, name, description, category, current_price, stock, available, rarity, discount] ->
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
      Sql.query!(
        """
        SELECT product_id, category, discount_percent, floor(extract(epoch from ends_at))::bigint
        FROM economy_market_sales
        WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now()
        ORDER BY starts_at DESC
        """
      ).rows
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
end
