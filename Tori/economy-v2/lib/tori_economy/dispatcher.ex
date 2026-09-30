defmodule ToriEconomy.Dispatcher do
  @moduledoc "Transport-neutral command boundary. Discord adapters must validate then call here."
  alias ToriEconomy.{
    Accounts,
    Activity,
    Consumables,
    Equipment,
    Market,
    Marketplace,
    Persona,
    Progression,
    Queries,
    Shop
  }

  def execute(request) do
    result =
      case request.operation do
        operation when operation in ["market.product", "market.history"] ->
          Market.execute(request)

        operation when operation in ["shop.rotation", "shop.item", "shop.purchase"] ->
          Shop.execute(request)

        operation
        when operation in [
               "inventory.equip",
               "inventory.unequip",
               "inventory.cosmetic.select",
               "inventory.cosmetic.clear"
             ] ->
          Equipment.execute(request)

        operation
        when operation in [
               "progression.snapshot",
               "progression.grant",
               "career.snapshot",
               "career.select",
               "career.practice"
             ] ->
          Progression.execute(request)

        "activity.perform" ->
          Activity.execute(request)

        operation when operation in ["inventory.consume", "inventory.effects"] ->
          Consumables.execute(request)

        "marketplace." <> _ ->
          Marketplace.execute(request)

        operation
        when operation in [
               "inventory.list",
               "shop.catalog",
               "wallet.leaderboard",
               "profile.snapshot",
               "inventory.cosmetics",
               "inventory.item",
               "wardrobe.list"
             ] ->
          Queries.execute(request)

        _ ->
          Accounts.execute(request)
      end

    record_mood_influence(result)

    case result do
      {:ok, %{"status" => "ok", "result" => %{"presentation_key" => key} = domain} = response} ->
        context = context_for(request.operation)
        variables = variables_for(domain)
        phrase = Persona.render(key, variables, Persona.snapshot(context: context))
        {:ok, Map.put(response, "presentation", %{"key" => key, "text" => phrase})}

      other ->
        other
    end
  end

  defp context_for("shop." <> _), do: :shop
  defp context_for("marketplace." <> _), do: :shop
  defp context_for("progression." <> _), do: :profile
  defp context_for("career." <> _), do: :profile
  defp context_for("inventory.cosmetic." <> _), do: :inventory
  defp context_for("activity." <> _), do: :activities
  defp context_for(_), do: :general

  defp variables_for(%{"type" => "shop_purchase"} = result),
    do: %{"item_name" => result["item_name"], "amount" => result["spent"]}

  defp variables_for(%{"type" => "shop_rotation"} = result),
    do: %{
      "theme" => String.replace(result["theme"], "_", " "),
      "minutes" => div(result["refresh_in_seconds"] || 0, 60)
    }

  defp variables_for(%{"type" => "shop_item"} = result),
    do: %{
      "item_name" => result["name"],
      "amount" => result["unit_price"],
      "rarity" => result["rarity"]
    }

  defp variables_for(%{"type" => "marketplace_purchase"} = result),
    do: %{"item_name" => result["item_name"], "amount" => result["price"]}

  defp variables_for(%{"type" => "marketplace_listing"} = result),
    do: %{
      "item_name" => result["item_name"],
      "amount" => result["ask_price"],
      "quantity" => result["quantity"]
    }

  defp variables_for(%{"type" => "xp_grant", "level_up" => true} = result),
    do: %{"level" => result["level"], "amount" => result["xp_awarded"]}

  defp variables_for(%{"type" => "xp_grant"} = result), do: %{"amount" => result["xp_awarded"]}

  defp variables_for(%{"type" => "career_practice"} = result),
    do: %{"amount" => result["xp_awarded"], "career" => result["career_name"]}

  defp variables_for(%{"type" => "career_selected"} = result),
    do: %{"career" => result["career_name"]}

  defp variables_for(%{"type" => "loadout"} = result),
    do: %{"item_name" => result["item_id"], "slot" => result["slot"]}

  defp variables_for(%{"type" => "cosmetic"} = result),
    do: %{"item_name" => result["item_id"], "slot" => result["slot"]}

  defp variables_for(%{"type" => "activity"} = result),
    do: %{"item_name" => result["drop_item_id"], "amount" => result["credits"]}

  defp variables_for(%{"type" => "consumable_used"} = result),
    do: %{"item_name" => result["item_name"], "expires_at" => result["expires_at_ms"]}

  defp variables_for(_), do: %{}

  @doc false
  def mood_event_for_result(%{"type" => "career_practice"}), do: nil

  def mood_event_for_result(domain) when is_map(domain) do
    cond do
      domain["rare_drop"] == true ->
        :rare_drop

      domain["level_up"] == true ->
        :level_up

      domain["type"] == "activity" ->
        :successful_activity

      domain["type"] in ["marketplace_purchase", "shop_purchase"] ->
        :celebration

      true ->
        nil
    end
  end

  def mood_event_for_result(_), do: nil

  defp record_mood_influence({:ok, %{"status" => "ok", "result" => domain}}) do
    if event = mood_event_for_result(domain) do
      try do
        ToriEconomy.Persona.Mood.record(event)
      catch
        :exit, _ -> :ok
      end
    end
  end

  defp record_mood_influence(_), do: :ok
end
