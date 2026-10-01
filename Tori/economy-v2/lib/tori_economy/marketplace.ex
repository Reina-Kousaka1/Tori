defmodule ToriEconomy.Marketplace do
  @moduledoc "Escrowed user listings; isolated-test writes only until exclusive ownership cutover."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Sql}

  @page_size 8

  def execute(%{operation: "marketplace.inspect"} = request) do
    case Sql.query!(
           """
             SELECT l.listing_id,l.seller_user_id,l.item_id,c.name,c.description,c.category,c.rarity,
                    l.quantity,l.ask_price,floor(extract(epoch from l.expires_at))::bigint
             FROM economy_v2_marketplace_listings l
             JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
             WHERE l.listing_id=$1 AND l.status='ACTIVE' AND l.expires_at>now()
           """,
           [request.args["listing_id"]]
         ).rows do
      [[id, seller, item_id, name, description, category, rarity, quantity, price, expires]] ->
        {:ok,
         %{
           "request_id" => request.request_id,
           "status" => "ok",
           "result" => %{
             "type" => "marketplace_listing_detail",
             "listing_id" => id,
             "seller_user_id" => seller,
             "item_id" => item_id,
             "name" => name,
             "description" => description,
             "category" => category,
             "rarity" => rarity,
             "quantity" => Integer.to_string(quantity),
             "ask_price" => Integer.to_string(price),
             "expires_at_epoch" => Integer.to_string(expires)
           }
         }}

      [] ->
        {:ok,
         %{
           "request_id" => request.request_id,
           "status" => "error",
           "error" => %{"code" => "ITEM_NOT_AVAILABLE", "retryable" => false}
         }}
    end
  end

  def execute(%{operation: "marketplace.browse"} = request) do
    category = Map.get(request.args, "category", "all")

    [[total]] =
      Sql.query!(
        """
          SELECT count(*) FROM economy_v2_marketplace_listings l
          JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
          WHERE l.status='ACTIVE' AND l.expires_at>now() AND ($1='all' OR c.category=$1)
        """,
        [category]
      ).rows

    total_pages = max(1, div(total + @page_size - 1, @page_size))
    page = min(Map.get(request.args, "page", 0), total_pages - 1)

    listings =
      Sql.query!(
        """
          SELECT l.listing_id,l.seller_user_id,l.item_id,c.name,c.category,c.rarity,
                 l.quantity,l.ask_price,floor(extract(epoch from l.expires_at))::bigint
          FROM economy_v2_marketplace_listings l
          JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
          WHERE l.status='ACTIVE' AND l.expires_at>now() AND ($1='all' OR c.category=$1)
          ORDER BY l.created_at DESC,l.listing_id LIMIT $2 OFFSET $3
        """,
        [category, @page_size, page * @page_size]
      ).rows
      |> Enum.map(fn [id, seller, item, name, item_category, rarity, qty, price, expires] ->
        %{
          "listing_id" => id,
          "seller_user_id" => seller,
          "item_id" => item,
          "name" => name,
          "category" => item_category,
          "rarity" => rarity,
          "quantity" => Integer.to_string(qty),
          "ask_price" => Integer.to_string(price),
          "expires_at_epoch" => Integer.to_string(expires)
        }
      end)

    {:ok,
     %{
       "request_id" => request.request_id,
       "status" => "ok",
       "result" => %{
         "type" => "marketplace_listings",
         "listings" => listings,
         "category" => category,
         "page" => page,
         "page_size" => 20,
         "total_items" => total,
         "total_pages" => total_pages
       }
     }}
  end

  def execute(request) do
    Idempotency.run(request, fn -> change(request) end,
      skip_persist_errors: [
        "ITEM_NOT_FOUND",
        "ITEM_NOT_OWNED",
        "ITEM_NOT_AVAILABLE",
        "INSUFFICIENT_FUNDS",
        "INVALID_TARGET",
        "INVALID_AMOUNT",
        "MAX_STACK_REACHED",
        "ALREADY_OWNED"
      ]
    )
  end

  defp change(%{operation: "marketplace.list"} = request) do
    seller = request.context["actor_user_id"]
    item_id = request.args["item_id"]
    quantity = request.args["quantity"]

    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [
      seller
    ])

    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [seller])

    with {:ok, item} <- Catalog.fetch_for_update(item_id),
         true <- item.tradeable || {:error, "ITEM_NOT_AVAILABLE"},
         {:ok, _} <-
           Inventory.take(
             seller,
             item_id,
             quantity,
             request,
             "listing_escrow",
             "MARKETPLACE_ESCROW"
           ) do
      listing_id = String.replace(request.idempotency_key, "discord-interaction:", "")

      Sql.query!(
        """
        INSERT INTO economy_v2_marketplace_listings
          (listing_id,seller_user_id,item_id,quantity,ask_price,status,expires_at)
        VALUES ($1,$2,$3,$4,$5,'ACTIVE',now()+($6::integer * interval '1 hour'))
        """,
        [
          listing_id,
          seller,
          item_id,
          quantity,
          String.to_integer(request.args["ask_price"]),
          request.args["expires_hours"]
        ]
      )

      ok(%{
        "type" => "marketplace_listing",
        "listing_id" => listing_id,
        "item_id" => item_id,
        "item_name" => item.name,
        "quantity" => quantity,
        "ask_price" => request.args["ask_price"],
        "presentation_key" => "marketplace.list.success"
      })
    else
      {:error, code} -> error(code)
    end
  end

  defp change(%{operation: "marketplace.cancel"} = request) do
    listing_id = request.args["listing_id"]
    actor = request.context["actor_user_id"]

    with {:ok, [^actor, item_id, quantity]} <- locked_listing(listing_id, true),
         {:ok, _} <- Inventory.restore_escrow(actor, item_id, quantity, request) do
      Sql.query!(
        "UPDATE economy_v2_marketplace_listings SET status='CANCELLED',closed_at=now() WHERE listing_id=$1",
        [listing_id]
      )

      ok(%{
        "type" => "marketplace_cancelled",
        "listing_id" => listing_id,
        "presentation_key" => "marketplace.cancel.success"
      })
    else
      {:ok, _} -> error("INVALID_TARGET")
      {:error, code} -> error(code)
    end
  end

  defp change(%{operation: "marketplace.buy"} = request) do
    listing_id = request.args["listing_id"]
    buyer = request.context["actor_user_id"]
    # Listing lock serializes buyers and cancellation. Account locks use stable order.
    with {:ok, [seller, item_id, quantity]} <- locked_listing(listing_id),
         true <- seller != buyer || {:error, "INVALID_TARGET"},
         {:ok, item} <- Catalog.fetch_for_update(item_id) do
      [[price]] =
        Sql.query!("SELECT ask_price FROM economy_v2_marketplace_listings WHERE listing_id=$1", [
          listing_id
        ]).rows

      Enum.each(Enum.sort([seller, buyer]), fn user ->
        Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [
          user
        ])

        Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])
      end)

      [[buyer_balance]] =
        Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [buyer]).rows

      [[seller_balance]] =
        Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [seller]).rows

      cond do
        buyer_balance < price ->
          error("INSUFFICIENT_FUNDS")

        seller_balance > 9_223_372_036_854_775_807 - price ->
          error("INVALID_AMOUNT")

        true ->
          settle(
            request,
            listing_id,
            buyer,
            seller,
            item,
            quantity,
            price,
            buyer_balance,
            seller_balance
          )
      end
    else
      {:error, code} -> error(code)
    end
  end

  defp settle(
         request,
         listing_id,
         buyer,
         seller,
         item,
         quantity,
         price,
         buyer_balance,
         seller_balance
       ) do
    with {:ok, _} <-
           Inventory.grant(
             buyer,
             item,
             quantity,
             request,
             "market_delivery",
             "MARKETPLACE_PURCHASE"
           ) do
      Sql.query!("UPDATE economy_accounts SET balance=$2,updated_at=now() WHERE user_id=$1", [
        buyer,
        buyer_balance - price
      ])

      Sql.query!("UPDATE economy_accounts SET balance=$2,updated_at=now() WHERE user_id=$1", [
        seller,
        seller_balance + price
      ])

      Enum.each(
        [
          {buyer, "market_debit", -price, buyer_balance - price, seller, "MARKETPLACE_BUY"},
          {seller, "market_credit", price, seller_balance + price, buyer, "MARKETPLACE_SELL"}
        ],
        fn {user, leg, delta, after_balance, other, reason} ->
          Sql.query!(
            """
            INSERT INTO economy_v2_ledger_entries
              (user_id,request_key,leg,delta,balance_after,reason_code,guild_id,counterparty_user_id)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
            """,
            [
              user,
              request.idempotency_key,
              leg,
              delta,
              after_balance,
              reason,
              request.context["guild_id"],
              other
            ]
          )
        end
      )

      Sql.query!(
        """
        UPDATE economy_v2_marketplace_listings SET status='SOLD',buyer_user_id=$2,closed_at=now()
        WHERE listing_id=$1
        """,
        [listing_id, buyer]
      )

      ok(%{
        "type" => "marketplace_purchase",
        "listing_id" => listing_id,
        "item_id" => item.id,
        "item_name" => item.name,
        "quantity" => quantity,
        "price" => Integer.to_string(price),
        "presentation_key" => "marketplace.purchase.success"
      })
    else
      {:error, code} -> error(code)
    end
  end

  defp locked_listing(id, include_expired \\ false) do
    case Sql.query!(
           """
             SELECT seller_user_id,item_id,quantity FROM economy_v2_marketplace_listings
             WHERE listing_id=$1 AND status='ACTIVE' AND ($2 OR expires_at>now()) FOR UPDATE
           """,
           [id, include_expired]
         ).rows do
      [row] -> {:ok, row}
      [] -> {:error, "ITEM_NOT_AVAILABLE"}
    end
  end

  defp ok(result), do: %{"status" => "ok", "result" => result}
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
