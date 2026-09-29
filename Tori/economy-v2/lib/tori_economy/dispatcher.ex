defmodule ToriEconomy.Dispatcher do
  @moduledoc "Transport-neutral command boundary. Discord adapters must validate then call here."
  alias ToriEconomy.{Accounts, Equipment, Market, Marketplace, Persona, Progression, Queries, Shop}

  def execute(request) do
    result = case request.operation do
      operation when operation in ["market.product", "market.history"] -> Market.execute(request)
      operation when operation in ["shop.rotation", "shop.purchase"] -> Shop.execute(request)
      operation when operation in ["inventory.equip", "inventory.unequip"] -> Equipment.execute(request)
      operation when operation in ["progression.snapshot", "progression.grant"] -> Progression.execute(request)
      "marketplace." <> _ -> Marketplace.execute(request)
      operation when operation in ["inventory.list", "shop.catalog", "wallet.leaderboard", "profile.snapshot"] -> Queries.execute(request)
      _ -> Accounts.execute(request)
    end

    case result do
      {:ok, %{"status" => "ok", "result" => %{"presentation_key" => key} = domain} = response} ->
        context = context_for(request.operation)
        variables = variables_for(domain)
        phrase = Persona.render(key, variables, Persona.snapshot(context: context))
        {:ok, Map.put(response, "presentation", %{"key" => key, "text" => phrase})}
      other -> other
    end
  end

  defp context_for("shop." <> _), do: :shop
  defp context_for("marketplace." <> _), do: :shop
  defp context_for("progression." <> _), do: :profile
  defp context_for(_), do: :general

  defp variables_for(%{"type" => "shop_purchase"} = result), do:
    %{"item_name" => result["item_name"], "amount" => result["spent"]}
  defp variables_for(%{"type" => "marketplace_purchase"} = result), do:
    %{"item_name" => result["item_name"], "amount" => result["price"]}
  defp variables_for(%{"type" => "xp_grant"} = result), do:
    %{"amount" => result["xp_awarded"]}
  defp variables_for(_), do: %{}
end
