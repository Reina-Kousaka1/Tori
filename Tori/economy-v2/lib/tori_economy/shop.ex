defmodule ToriEconomy.Shop do
  @moduledoc "Persisted shop drops, user-facing catalog state and atomic purchases."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}
  alias ToriEconomy.Persona.Season
  alias ToriEconomy.Shop.Rotation

  @page_size 10

  def execute(%{operation: "shop.rotation"} = request) do
    case Rotation.current() do
      {:ok, rotation} ->
        user = request.context["actor_user_id"]
        items = annotate_items(rotation["items"], user)
        category = Map.get(request.args, "category", "all")
        visible = Enum.filter(items, &(category == "all" or &1["category"] == category))
        total = length(visible)
        pages = max(1, div(total + @page_size - 1, @page_size))
        page = min(Map.get(request.args, "page", 0), pages - 1)
        selected = Enum.slice(visible, page * @page_size, @page_size)

        [[now_epoch]] =
          Sql.query!("SELECT floor(extract(epoch from now())::numeric)::bigint").rows

        result =
          rotation
          |> Map.put("items", selected)
          |> Map.put("rare_drop", Enum.count(items, &(&1["rarity"] in ["rare", "special"])) >= 3)
          |> Map.put("category", category)
          |> Map.put("page", page)
          |> Map.put("page_size", @page_size)
          |> Map.put("total_items", total)
          |> Map.put("total_pages", pages)
          |> Map.put("presentation_key", "shop.rotation.ready")
          |> Map.put(
            "refresh_in_seconds",
            max(0, String.to_integer(rotation["ends_at_epoch"]) - now_epoch)
          )

        {:ok, %{"request_id" => request.request_id, "status" => "ok", "result" => result}}

      error ->
        error
    end
  end

  def execute(%{operation: "shop.styles"} = request) do
    user = request.context["actor_user_id"]
    category = Map.get(request.args, "category", "all")

    items =
      Catalog.list_active()
      |> Enum.filter(&(category == "all" or &1.category == category))
      |> Enum.map(&catalog_item/1)
      |> annotate_items(user)

    total = length(items)
    pages = max(1, div(total + @page_size - 1, @page_size))
    page = min(Map.get(request.args, "page", 0), pages - 1)

    {:ok,
     %{
       "request_id" => request.request_id,
       "status" => "ok",
       "result" => %{
         "type" => "shop_catalog",
         "items" => Enum.slice(items, page * @page_size, @page_size),
         "category" => category,
         "page" => page,
         "total_pages" => pages,
         "total_items" => total
       }
     }}
  end

  def execute(%{operation: "shop.item", args: %{"period_key" => "catalog"}} = request) do
    item = Catalog.list_active() |> Enum.find(&(&1.id == request.args["item_id"]))

    if item && catalog_available?(item) do
      [detail] = annotate_items([catalog_item(item)], request.context["actor_user_id"])

      {:ok,
       %{
         "request_id" => request.request_id,
         "status" => "ok",
         "result" => Map.merge(detail, %{"type" => "shop_item", "period_key" => "catalog"})
       }}
    else
      {:error, "ITEM_NOT_AVAILABLE"}
    end
  end

  def execute(%{operation: "shop.item"} = request) do
    user = request.context["actor_user_id"]
    requested_period = request.args["period_key"]

    with {:ok, rotation} <- Rotation.current(),
         true <- rotation["period_key"] == requested_period || {:error, "ITEM_NOT_AVAILABLE"},
         item when not is_nil(item) <-
           Enum.find(rotation["items"], &(&1["item_id"] == request.args["item_id"])) do
      [item] = annotate_items([item], user)

      detail =
        item |> Map.put("type", "shop_item") |> Map.put("period_key", rotation["period_key"])

      detail =
        if item["rarity"] in ["rare", "special"],
          do: Map.put(detail, "presentation_key", "shop.item.rare"),
          else: detail

      {:ok, %{"request_id" => request.request_id, "status" => "ok", "result" => detail}}
    else
      {:error, code} -> {:error, code}
      nil -> {:error, "ITEM_NOT_AVAILABLE"}
      false -> {:error, "ITEM_NOT_AVAILABLE"}
    end
  end

  def execute(%{operation: "shop.purchase"} = request) do
    Idempotency.run(request, fn -> purchase(request) end,
      skip_persist_errors: [
        "ITEM_NOT_FOUND",
        "ITEM_NOT_AVAILABLE",
        "INSUFFICIENT_FUNDS",
        "INVALID_QUANTITY",
        "MAX_STACK_REACHED",
        "ALREADY_OWNED",
        "REQUIREMENT_NOT_MET"
      ]
    )
  end

  defp catalog_item(item) do
    %{
      "item_id" => item.id,
      "name" => item.name,
      "description" => item.description,
      "category" => item.category,
      "subcategory" => item.subcategory,
      "rarity" => item.rarity,
      "unit_price" => Integer.to_string(item.buy_price),
      "remaining" => nil,
      "level_requirement" => item.level_requirement,
      "career_requirement" => item.career_requirement,
      "career_level_requirement" => item.career_level_requirement,
      "season" => item.season,
      "tags" => item.tags,
      "available" => catalog_available?(item)
    }
  end

  defp catalog_available?(item) do
    current = Season.current()

    item.active and is_nil(Map.get(item.metadata, "stock_limit")) and
      (is_nil(item.season) or item.season == Atom.to_string(current) or
         (item.season == "winter" and current == :christmas))
  end

  defp annotate_items(items, user) do
    ids = Enum.map(items, & &1["item_id"])

    owned =
      case ids do
        [] ->
          %{}

        _ ->
          Sql.query!(
            "SELECT item_id,quantity FROM economy_inventory WHERE user_id=$1 AND item_id=ANY($2::text[])",
            [user, ids]
          ).rows
          |> Map.new(fn [id, quantity] -> {id, quantity} end)
      end

    level =
      case Sql.query!(
             """
               SELECT coalesce(max(level),1) FROM economy_v2_xp_thresholds
               WHERE required_xp<=coalesce((SELECT xp FROM economy_v2_account_progress WHERE user_id=$1),0)
             """,
             [user]
           ).rows do
        [[value]] -> value
      end

    career_levels =
      Sql.query!(
        """
          SELECT p.career_code,coalesce(max(t.level),1)
          FROM economy_v2_career_progress p
          LEFT JOIN economy_v2_xp_thresholds t ON t.required_xp<=coalesce(p.xp,0)
          WHERE p.user_id=$1 GROUP BY p.career_code
        """,
        [user]
      ).rows
      |> Map.new(fn [code, career_level] -> {code, career_level} end)

    Enum.map(items, fn item ->
      quantity = Map.get(owned, item["item_id"], 0)
      remaining = item["remaining"]
      available = Map.get(item, "available", true) and
                    (is_nil(remaining) or String.to_integer(remaining) > 0)

      career_ok =
        is_nil(item["career_requirement"]) or
          (Map.get(career_levels, item["career_requirement"]) || 0) >=
            item["career_level_requirement"]

      eligible = level >= item["level_requirement"] and career_ok

      state =
        cond do
          not available -> "unavailable"
          not eligible -> "locked"
          quantity > 0 -> "owned"
          true -> "available"
        end

      item
      |> Map.put("owned_quantity", Integer.to_string(quantity))
      |> Map.put("player_level", level)
      |> Map.put("eligible", eligible)
      |> Map.put("career_requirement", item["career_requirement"])
      |> Map.put("career_level_requirement", item["career_level_requirement"])
      |> Map.put("available", available)
      |> Map.put("state", state)
    end)
  end

  defp purchase(request) do
    user_id = request.context["actor_user_id"]
    item_id = request.args["item_id"]
    amount = request.args["quantity"]
    period_key = request.args["period_key"]

    # Serialize wallet and inventory mutations on the global account row.
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [
      user_id
    ])

    [[balance]] =
      Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user_id]).rows

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         {:ok, unit_price} <- available_item(period_key, item, amount),
         :ok <- requirements(user_id, item, amount),
         total = unit_price * amount,
         true <- balance >= total || {:error, "INSUFFICIENT_FUNDS"},
         {:ok, after_quantity} <-
           Inventory.grant(user_id, item, amount, request, "shop_grant", "SHOP_PURCHASE") do
      remaining = balance - total

      Sql.query!("UPDATE economy_accounts SET balance=$2,updated_at=now() WHERE user_id=$1", [
        user_id,
        remaining
      ])

      Sql.query!(
        """
        INSERT INTO economy_v2_ledger_entries
          (user_id,request_key,leg,delta,balance_after,reason_code,guild_id)
        VALUES ($1,$2,'shop_debit',$3,$4,'SHOP_PURCHASE',$5)
        """,
        [user_id, request.idempotency_key, -total, remaining, request.context["guild_id"]]
      )

      if period_key != "catalog" do
        Sql.query!(
          "UPDATE economy_v2_shop_rotation_items SET sold=sold+$3 WHERE period_key=$1 AND item_id=$2",
          [String.to_integer(period_key), item_id, amount]
        )
      end

      ok(%{
        "type" => "shop_purchase",
        "item_id" => item_id,
        "item_name" => item.name,
        "quantity" => amount,
        "quantity_after" => Integer.to_string(after_quantity),
        "spent" => Integer.to_string(total),
        "balance" => Integer.to_string(remaining),
        "presentation_key" => "shop.purchase.success"
      })
    else
      {:error, code} -> error(code)
      false -> error("INSUFFICIENT_FUNDS")
    end
  end

  defp available_item("catalog", item, _amount) do
    if catalog_available?(item) and is_integer(item.buy_price),
      do: {:ok, item.buy_price},
      else: {:error, "ITEM_NOT_AVAILABLE"}
  end

  defp available_item(period_key, item, amount) do
    case Sql.query!(
           """
           SELECT r.unit_price,r.stock_limit,r.sold
           FROM economy_v2_shop_rotation_items r
           JOIN economy_v2_shop_rotations s ON s.period_key=r.period_key
           WHERE r.period_key=$1 AND r.item_id=$2 AND s.starts_at<=now() AND s.ends_at>now()
           FOR UPDATE OF r
           """,
           [String.to_integer(period_key), item.id]
         ).rows do
      [[price, limit, sold]] when is_nil(limit) or sold <= limit - amount -> {:ok, price}
      _ -> {:error, "ITEM_NOT_AVAILABLE"}
    end
  end

  defp requirements(user_id, item, amount) do
    current = Inventory.quantity(user_id, item.id)

    level =
      case Sql.query!(
             """
             SELECT coalesce(max(level),1) FROM economy_v2_xp_thresholds
             WHERE required_xp <= coalesce((SELECT xp FROM economy_v2_account_progress WHERE user_id=$1),0)
             """,
             [user_id]
           ).rows do
        [[value]] -> value
      end

    cond do
      not item.active ->
        {:error, "ITEM_NOT_AVAILABLE"}

      amount < 1 or amount > 100 ->
        {:error, "INVALID_QUANTITY"}

      current > (item.max_stack || 9_223_372_036_854_775_807) - amount ->
        {:error, "MAX_STACK_REACHED"}

      not item.stackable and (current > 0 or amount > 1) ->
        {:error, "ALREADY_OWNED"}

      level < item.level_requirement ->
        {:error, "REQUIREMENT_NOT_MET"}

      not career_unlocked?(user_id, item.career_requirement, item.career_level_requirement) ->
        {:error, "REQUIREMENT_NOT_MET"}

      true ->
        :ok
    end
  end

  defp career_unlocked?(_user, nil, _required_level), do: true

  defp career_unlocked?(user, required_career, required_level) do
    case Sql.query!(
           """
             SELECT p.career_code,coalesce(max(t.level),1)
             FROM economy_v2_career_progress p
             LEFT JOIN economy_v2_xp_thresholds t ON t.required_xp<=coalesce(p.xp,0)
             WHERE p.user_id=$1 GROUP BY p.career_code
           """,
           [user]
         ).rows do
      [[^required_career, career_level]] -> career_level >= required_level
      _ -> false
    end
  end

  defp ok(result), do: %{"status" => "ok", "result" => result}
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
