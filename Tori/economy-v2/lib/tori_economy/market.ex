defmodule ToriEconomy.Market do
  @moduledoc "Read-only view of Java's existing dynamic market tables. No quotes or prices are written."
  alias ToriEconomy.Sql

  def execute(%{operation: "market.product"} = request) do
    id = request.args["product_id"]

    case Sql.query!(
           """
           SELECT product_id, name, description, category, current_price, base_price,
                  minimum_price, maximum_price, stock, available, rarity
           FROM economy_market_products WHERE product_id=$1
           """,
           [id]
         ).rows do
      [[product_id, name, description, category, price, base, minimum, maximum, stock, available, rarity]] ->
        ok(request, %{
          "type" => "market_product", "product_id" => product_id, "name" => name,
          "description" => description, "category" => category,
          "current_price" => Integer.to_string(price), "base_price" => Integer.to_string(base),
          "minimum_price" => Integer.to_string(minimum), "maximum_price" => Integer.to_string(maximum),
          "stock" => Integer.to_string(stock), "available" => available, "rarity" => rarity
        })

      [] ->
        {:ok, %{"request_id" => request.request_id, "status" => "error",
                "error" => %{"code" => "ITEM_NOT_FOUND", "retryable" => false}}}
    end
  end

  def execute(%{operation: "market.history"} = request) do
    id = request.args["product_id"]
    limit = Map.get(request.args, "limit", 10)

    points =
      Sql.query!(
        """
        SELECT price, changed_at, reason FROM economy_market_price_history
        WHERE product_id=$1 ORDER BY history_id DESC LIMIT $2
        """,
        [id, limit]
      ).rows
      |> Enum.map(fn [price, changed_at, reason] ->
        %{"price" => Integer.to_string(price), "changed_at" => DateTime.to_iso8601(changed_at),
          "reason" => reason}
      end)

    ok(request, %{"type" => "market_history", "product_id" => id, "points" => points})
  end

  defp ok(request, result),
    do: {:ok, %{"request_id" => request.request_id, "status" => "ok", "result" => result}}
end
