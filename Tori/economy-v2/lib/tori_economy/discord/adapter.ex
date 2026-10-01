defmodule ToriEconomy.Discord.Adapter do
  @moduledoc "Discord adapter over the shared, validated Elixir domain boundary."
  alias ToriEconomy.{Contract, Dispatcher, Persona, WriteGate}

  @commands %{
    "profile" => :profile,
    "career" => :career,
    "marry" => :marry,
    "divorce" => :divorce,
    "marriage" => :marriage,
    "wardrobe" => :wardrobe,
    "marketplace" => :marketplace,
    "tori-profile-preview" => :profile,
    "tori-shop-preview" => :shop,
    "tori-wardrobe-preview" => :wardrobe,
    "tori-marketplace-preview" => :marketplace,
    "tori-career-preview" => :career,
    "tori-persona-preview" => :persona,
    "tori-consumable-preview" => :consumables,
    "tori-activity-preview" => :activities
  }
  @nostrum_commands ~w(profile career marry divorce marriage wardrobe marketplace)
  @shop_categories [
    {"All styles", "all"},
    {"Fashion", "fashion"},
    {"Accessories", "accessories"},
    {"Beauty", "beauty"},
    {"Ballet", "ballet"},
    {"Volleyball", "volleyball"},
    {"Cheer", "cheer"},
    {"Consumables", "consumables"},
    {"Collectibles", "collectibles"},
    {"Seasonal", "seasonal"}
  ]
  @shop_category_values Enum.map(@shop_categories, &elem(&1, 1))
  @shop_category_set MapSet.new(@shop_category_values)
  @mutations ~w(shop.purchase inventory.equip inventory.unequip
    inventory.cosmetic.select inventory.cosmetic.clear marketplace.list marketplace.buy
    marketplace.cancel career.select career.practice inventory.consume activity.perform
    marriage.propose marriage.accept marriage.decline marriage.cancel marriage.divorce)

  def preview_commands, do: Map.keys(@commands)
  def nostrum_commands, do: @nostrum_commands
  def shop_categories, do: @shop_categories

  @doc false
  def idempotency_key(operation, interaction_id)
      when operation in @mutations and is_integer(interaction_id) do
    "discord-interaction:" <> Integer.to_string(interaction_id)
  end

  def idempotency_key(_operation, _interaction_id), do: nil

  def supported_interaction?(interaction) do
    data = field(interaction, :data, %{})
    name = field(data, :name, nil)

    name in @nostrum_commands or
      (shop_enabled?() and (name == "shop" or match?({:component, _, _}, shop_navigation(interaction))))
  end

  defp shop_enabled? do
    System.get_env("TORI_NOSTRUM_ENABLED") == "true" and
      System.get_env("TORI_NOSTRUM_SHOP_ENABLED") == "true"
  end

  def component_interaction?(interaction) do
    data = field(interaction, :data, %{})
    is_binary(field(data, :custom_id, nil))
  end

  def handle(%{data: %{name: name}, id: id, guild_id: guild, channel_id: channel} = interaction)
      when is_binary(name) and is_integer(id) and is_integer(guild) and is_integer(channel) do
    preview = if name == "shop" and shop_enabled?(), do: :shop, else: Map.get(@commands, name)

    case preview do
      nil -> :ignore
      :persona -> persona_preview()
      preview -> domain_preview(preview, interaction, id, guild, channel)
    end
  end

  def handle(_), do: :ignore

  def handle_component(interaction) when is_map(interaction) do
    case shop_navigation(interaction) do
      {:component, category, page} ->
        options = [%{name: "category", value: category}, %{name: "page", value: page}]
        name = if(shop_enabled?(), do: "shop", else: "tori-shop-preview")
        data = %{name: name, options: [%{name: "browse", options: options}]}
        handle(Map.put(interaction, :data, data))

      _ ->
        :ignore
    end
  end

  def handle_component(_), do: :ignore

  def shop_navigation(interaction) do
    data = field(interaction, :data, %{})

    case field(data, :custom_id, nil) do
      "tori-shop-category" ->
        case field(data, :values, []) do
          [category] when category in @shop_category_values -> {:component, category, 0}
          _ -> :ignore
        end

      "tori-shop-page:" <> suffix ->
        case String.split(suffix, ":") do
          [category, page] ->
            with true <- MapSet.member?(@shop_category_set, category),
                 {number, ""} <- Integer.parse(page),
                 true <- number in 0..1000 do
              {:component, category, number}
            else
              _ -> :ignore
            end

          _ ->
            :ignore
        end

      _ ->
        if field(data, :name, nil) in ["shop", "tori-shop-preview"] do
          {subcommand, options} = command_options(interaction)

          if subcommand == "browse" do
            category = value(options, "category") || "all"
            page = integer(options, "page", 0)

            if MapSet.member?(@shop_category_set, category) and page in 0..1000 do
              {:command, category, page}
            else
              :ignore
            end
          else
            :ignore
          end
        else
          :ignore
        end
    end
  end

  defp domain_preview(preview, interaction, interaction_id, guild, channel) do
    with {:ok, user} <- actor_id(interaction),
         {subcommand, options} <- command_options(interaction),
         {:ok, operation, args} <- operation_for(preview, subcommand, options),
         key = idempotency_key(operation, interaction_id),
         {:ok, request} <-
           Contract.validate(%{
             "request_id" => Ecto.UUID.generate(),
             "idempotency_key" => key,
             "operation" => operation,
             "context" => preview_context(preview, options, user, guild, channel),
             "args" => args
           }),
         :ok <- if(operation in @mutations, do: WriteGate.authorize(operation), else: :ok),
         {:ok, response} <- Dispatcher.execute(request),
         {:ok, content} <- render_result(preview, response, user) do
      {:ok, content}
    else
      {:error, "READ_ONLY"} ->
        {:error,
         "Tori economy writes are currently disabled."}

      {:error, code} when is_binary(code) ->
        {:error, error_text(code)}

      _ ->
        {:error, "This Tori command is currently unavailable."}
    end
  rescue
    _ -> {:error, "This Tori command is currently unavailable."}
  end

  defp operation_for(:profile, _subcommand, _options), do: {:ok, "profile.snapshot", %{}}

  defp operation_for(:shop, "catalog", options),
    do: {:ok, "shop.styles", %{"category" => value(options, "category") || "all", "page" => integer(options, "page", 0)}}

  defp operation_for(:shop, "item", options),
    do:
      {:ok, "shop.item",
       %{"item_id" => value(options, "item_id"), "period_key" => value(options, "period_key") || "catalog"}}

  defp operation_for(:shop, "buy", options),
    do:
      {:ok, "shop.purchase",
       %{
         "item_id" => value(options, "item_id"),
         "period_key" => value(options, "period_key"),
         "quantity" => integer(options, "quantity", 1)
       }}

  defp operation_for(:shop, _subcommand, options),
    do:
      {:ok, "shop.rotation",
       %{"category" => value(options, "category") || "all", "page" => integer(options, "page", 0)}}

  defp operation_for(:wardrobe, "equip", options),
    do:
      {:ok, "inventory.equip",
       %{"item_id" => value(options, "item_id"), "slot" => value(options, "slot")}}

  defp operation_for(:wardrobe, "unequip", options),
    do: {:ok, "inventory.unequip", %{"slot" => value(options, "slot")}}

  defp operation_for(:wardrobe, "cosmetic", options),
    do:
      {:ok, "inventory.cosmetic.select",
       %{"item_id" => value(options, "item_id"), "slot" => value(options, "slot")}}

  defp operation_for(:wardrobe, "clear", options),
    do: {:ok, "inventory.cosmetic.clear", %{"slot" => value(options, "slot")}}

  defp operation_for(:wardrobe, "inspect", options),
    do: {:ok, "inventory.item", %{"item_id" => value(options, "item_id")}}

  defp operation_for(:wardrobe, "list", options),
    do:
      {:ok, "wardrobe.list",
       %{"category" => value(options, "category") || "all", "page" => integer(options, "page", 0)}}

  defp operation_for(:wardrobe, _subcommand, _options),
    do: {:ok, "wardrobe.list", %{"category" => "all", "page" => 0}}

  defp operation_for(:marketplace, "list", options),
    do:
      {:ok, "marketplace.list",
       %{
         "item_id" => value(options, "item_id"),
         "quantity" => integer(options, "quantity", 1),
         "ask_price" => value(options, "ask_price"),
         "expires_hours" => integer(options, "expires_hours", 24)
       }}

  defp operation_for(:marketplace, "inspect", options),
    do: {:ok, "marketplace.inspect", %{"listing_id" => value(options, "listing_id")}}

  defp operation_for(:marketplace, "buy", options),
    do: {:ok, "marketplace.buy", %{"listing_id" => value(options, "listing_id")}}

  defp operation_for(:marketplace, "cancel", options),
    do: {:ok, "marketplace.cancel", %{"listing_id" => value(options, "listing_id")}}

  defp operation_for(:marketplace, _subcommand, options),
    do:
      {:ok, "marketplace.browse",
       %{"category" => value(options, "category") || "all", "page" => integer(options, "page", 0)}}

  defp operation_for(:career, "select", options),
    do: {:ok, "career.select", %{"career_code" => value(options, "career_code")}}

  defp operation_for(:career, "activity", options),
    do:
      {:ok, "career.practice",
       %{
         "career_code" => value(options, "career_code"),
         "action_code" => value(options, "action_code")
       }}

  defp operation_for(:career, "practice", options),
    do:
      {:ok, "career.practice",
       %{"career_code" => value(options, "career_code"), "action_code" => "practice"}}

  defp operation_for(:career, _subcommand, _options), do: {:ok, "career.snapshot", %{}}

  defp operation_for(:marry, "propose", options),
    do: {:ok, "marriage.propose", %{"target_user_id" => value(options, "user")}}

  defp operation_for(:marry, action, _options) when action in ["accept", "decline", "cancel"],
    do: {:ok, "marriage." <> action, %{}}

  defp operation_for(:marriage, _subcommand, _options),
    do: {:ok, "marriage.snapshot", %{}}

  defp operation_for(:divorce, _subcommand, _options),
    do: {:ok, "marriage.divorce", %{}}

  defp operation_for(:consumables, "inspect", options),
    do: {:ok, "inventory.item", %{"item_id" => value(options, "item_id")}}

  defp operation_for(:consumables, "effects", _options), do: {:ok, "inventory.effects", %{}}

  defp operation_for(:consumables, _subcommand, options),
    do: {:ok, "inventory.consume", %{"item_id" => value(options, "item_id")}}

  defp operation_for(:activities, _subcommand, options),
    do: {:ok, "activity.perform", %{"activity" => value(options, "activity")}}

  defp render_preview(:shop, _response, %{"type" => "shop_catalog"} = result, _user) do
    lines =
      Enum.map(result["items"], fn item ->
        "#{rarity_mark(item["rarity"])} `#{item["item_id"]}` #{item["name"]} · #{item["unit_price"]} Credits · #{item["state"]}"
      end)

    [
      "Catalog · #{result["category"]} · page #{result["page"] + 1}/#{result["total_pages"]}",
      "Use /shop item or /shop buy with the item ID.",
      if(lines == [], do: "No items in this category.", else: Enum.join(lines, "\n"))
    ]
    |> Enum.join("\n")
    |> clip()
  end

  defp render_preview(:shop, _response, %{"type" => "shop_item"} = result, _user),
    do: format_shop_item(result)

  defp render_preview(:shop, response, %{"type" => "shop_rotation"} = result, user),
    do:
      [get_in(response, ["presentation", "text"]), format(:shop, result, user)]
      |> Enum.reject(&is_nil/1)
      |> Enum.join("\n")
      |> clip()

  defp render_preview(:shop, response, %{"type" => "shop_purchase"} = result, _user),
    do:
      "#{get_in(response, ["presentation", "text"])} Quantity: #{result["quantity"]} · Balance: #{result["balance"]} Credits."

  defp render_preview(:consumables, _response, %{"type" => "consumable_used"} = result, _user),
    do: format(:consumables, result, nil)

  defp render_preview(:consumables, _response, %{"type" => "inventory_item"} = result, _user),
    do: format(:consumables, result, nil)

  defp render_preview(:consumables, _response, %{"type" => "active_effects"} = result, _user),
    do: format_effects(result)

  defp render_preview(:career, response, %{"type" => "career_practice"} = result, _user) do
    message = get_in(response, ["presentation", "text"]) || "Career activity complete."

    "#{message}\n#{result["career_name"]} #{String.replace(result["action_code"], "_", " ")} · +#{result["xp_awarded"]} XP · +#{result["credits_awarded"]} Credits · career level #{result["career_level"]}."
  end

  defp render_preview(:marketplace, response, %{"type" => "marketplace_listing"} = result, _user),
    do: "#{get_in(response, ["presentation", "text"])} Listing ID: `#{result["listing_id"]}`."

  defp render_preview(
         :marketplace,
         _response,
         %{"type" => "marketplace_listing_detail"} = result,
         _user
       ),
       do: format_listing_detail(result)

  defp render_preview(preview, response, result, user),
    do: get_in(response, ["presentation", "text"]) || format(preview, result, user)

  @doc false
  def render_result(preview, %{"status" => "ok", "result" => result} = response, user),
    do: {:ok, render_preview(preview, response, result, user)}

  def render_result(_preview, %{"error" => %{"code" => code}}, _user),
    do: {:error, error_text(code)}

  def render_result(_preview, _, _user), do: {:error, "The request could not be completed."}

  defp command_options(interaction) do
    options = field(field(interaction, :data, %{}), :options, [])

    case options do
      [first | _] ->
        case field(first, :options, nil) do
          nested when is_list(nested) ->
            {field(first, :name, nil),
             Map.new(nested, fn option ->
               {field(option, :name, ""), field(option, :value, nil)}
             end)}

          _ ->
            {nil,
             Map.new(options, fn option ->
               {field(option, :name, ""), field(option, :value, nil)}
             end)}
        end

      _ ->
        {nil, %{}}
    end
  end

  defp value(options, name) do
    case Map.get(options, name) do
      value when is_binary(value) -> value
      value when is_integer(value) -> Integer.to_string(value)
      _ -> nil
    end
  end

  defp integer(options, name, default) do
    case Map.get(options, name) do
      value when is_integer(value) ->
        value

      value when is_binary(value) ->
        case Integer.parse(value) do
          {number, ""} -> number
          _ -> default
        end

      _ ->
        default
    end
  end

  defp preview_context(preview, options, user, guild, channel) do
    context = %{
      "actor_user_id" => user,
      "guild_id" => Integer.to_string(guild),
      "channel_id" => Integer.to_string(channel)
    }

    if preview in [:profile, :marriage] do
      case Map.fetch(options, "user") do
        {:ok, target} when is_integer(target) ->
          Map.put(context, "target_user_id", Integer.to_string(target))

        {:ok, target} when is_binary(target) ->
          Map.put(context, "target_user_id", target)

        {:ok, _invalid_target} ->
          Map.put(context, "target_user_id", "invalid")

        :error ->
          context
      end
    else
      context
    end
  end

  defp field(map, key, fallback) when is_map(map) do
    case Map.fetch(map, key) do
      {:ok, value} -> value
      :error -> Map.get(map, Atom.to_string(key), fallback)
    end
  end

  defp field(_, _, fallback), do: fallback

  defp format(:profile, result, user) do
    progress = result["progression"] || %{}

    xp_progress =
      if progress["next_level_xp"],
        do: "XP #{progress["xp"]} / #{progress["next_level_xp"]}",
        else: "XP #{progress["xp"]} · max configured level"

    career = progress["active_career"]
    profile_user = result["user_id"] || user
    outfit = Enum.map(result["loadout"] || [], fn item -> "#{item["slot"]}: #{item["name"]}" end)

    cosmetics =
      Enum.map(result["cosmetics"] || [], fn item -> "#{item["slot"]}: #{item["name"]}" end)

    activities =
      Enum.map(result["activities"] || [], fn item ->
        last = String.to_integer(item["last_at_ms"])

        if last > 0,
          do: "#{item["activity"]}: <t:#{div(last, 1000)}:R>",
          else: "#{item["activity"]}: not yet"
      end)

    [
      "Tori profile • <@#{profile_user}>",
      "Credits: #{result["balance"]}",
      "Level #{progress["level"] || 1} • #{xp_progress}",
      "Skill level #{progress["skill_level"] || 1} • #{progress["skill_xp"] || "0"} career XP",
      if(career,
        do: "Career: #{career["name"]} • level #{career["level"]} · #{career["xp"]} XP",
        else: "Career: none selected"
      ),
      if(outfit == [], do: "Outfit: empty", else: "Outfit: " <> Enum.join(outfit, " · ")),
      if(cosmetics == [], do: nil, else: "Style: " <> Enum.join(cosmetics, " · ")),
      if(result["relationship"],
        do: "Relationship: #{result["relationship"]["status"]} · <@#{result["relationship"]["partner_user_id"]}>",
        else: nil
      ),
      if(activities == [], do: nil, else: "Recent activity: " <> Enum.join(activities, " · "))
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
    |> clip()
  end

  defp format(preview, %{"type" => "marriage", "relationship" => relationship}, _user)
       when preview in [:marry, :divorce, :marriage] do
    case relationship do
      nil ->
        "No relationship recorded."

      %{"status" => status, "partner_user_id" => partner} ->
        "Relationship: #{status} · <@#{partner}>"
    end
  end

  defp format(:shop, result, _user) do
    items =
      Enum.map(result["items"], fn item ->
        seasonal = if item["season"], do: " · #{item["season"]}", else: ""
        ownership = if item["state"] == "owned", do: " ×#{item["owned_quantity"]}", else: ""

        "#{rarity_mark(item["rarity"])} `#{item["item_id"]}` #{item["name"]} · #{item["unit_price"]} Credits · #{item["state"]}#{ownership}#{seasonal}"
      end)

    refresh = div(String.to_integer(result["refresh_in_seconds"]), 60)

    [
      "#{String.replace(result["theme"], "_", " ")} drop · #{result["season"]} · drop `#{result["period_key"]}`",
      "Refreshes in about #{refresh} min · page #{result["page"] + 1}/#{result["total_pages"]}",
      "Use /shop item or /shop buy with the item ID and drop period.",
      if(items == [], do: "No items in this category.", else: Enum.join(items, "\n"))
    ]
    |> Enum.join("\n")
    |> clip()
  end

  defp format(:wardrobe, %{"type" => "inventory_item"} = item, _user) do
    slots = Enum.join(item["equip_slots"] || [], ", ")
    cosmetic = Enum.join(item["cosmetic_slots"] || [], ", ")

    [
      "#{item["name"]} · #{item["rarity"]}",
      item["description"],
      "Quantity: #{item["quantity"]} · #{item["category"]}/#{item["subcategory"]}",
      if(item["equipped"],
        do: "Equipped",
        else: if(slots == "", do: nil, else: "Equip slots: #{slots}")
      ),
      if(item["selected_cosmetic"],
        do: "Selected cosmetic",
        else: if(cosmetic == "", do: nil, else: "Cosmetic slots: #{cosmetic}")
      ),
      if(item["consumable"], do: "Consumable", else: nil),
      if(item["tradeable"], do: "Marketplace eligible", else: "Not tradeable")
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
    |> clip()
  end

  defp format(:wardrobe, result, _user) do
    owned = Enum.filter(result["items"], &(&1["equip_slots"] != [] or &1["cosmetic_slots"] != []))

    lines =
      Enum.map(owned, fn item ->
        marker = if item["equipped"] or item["selected_cosmetic"], do: " ✓", else: ""
        "#{item["name"]} ×#{item["quantity"]}#{marker}"
      end)

    heading =
      "Wardrobe · #{String.replace(result["category"] || "all", "_", " ")} · page #{result["page"] + 1}/#{result["total_pages"]}"

    if lines == [],
      do: heading <> "\nNo owned items on this page.",
      else: (heading <> "\n" <> Enum.join(lines, "\n")) |> clip()
  end

  defp format(:marketplace, result, _user) do
    lines =
      Enum.map(result["listings"], fn listing ->
        "• `#{listing["listing_id"]}` #{listing["name"]} ×#{listing["quantity"]} · #{listing["ask_price"]} Credits · seller `#{listing["seller_user_id"]}`"
      end)

    heading =
      "Marketplace · #{String.replace(result["category"] || "all", "_", " ")} · page #{result["page"] + 1}/#{result["total_pages"]}"

    if lines == [],
      do: heading <> "\nNo active listings on this page.",
      else: (heading <> "\n" <> Enum.join(Enum.take(lines, 12), "\n")) |> clip()
  end

  defp format(:consumables, %{"type" => "inventory_item"} = item, _user) do
    duration =
      if item["effect_duration_ms"],
        do: " · #{div(item["effect_duration_ms"], 60_000)} min",
        else: ""

    configured =
      if item["effect_active"],
        do: "configured: #{item["effect_code"]}#{duration}",
        else: "no configured effect"

    "#{item["name"]} · quantity #{item["quantity"]} · #{configured}"
  end

  defp format(:consumables, %{"type" => "consumable_used"} = result, _user),
    do:
      "Used #{result["item_name"]} · effect #{result["effect_code"]} · expires <t:#{div(String.to_integer(result["expires_at_ms"]), 1000)}:R>."

  defp format(:career, result, _user) do
    career = result["active_career"]

    options =
      Enum.map(result["careers"], fn entry ->
        marker = if career && career["code"] == entry["code"], do: " · selected", else: ""
        "• #{entry["name"]}: level #{entry["level"]}, #{entry["xp"]} XP#{marker}"
      end)

    activities =
      case career do
        %{"actions" => actions} ->
          Enum.map(actions, fn action ->
            status =
              case action["availability"] do
                "career_level" ->
                  "requires career level #{action["required_career_level"]}"

                "equipment" ->
                  "equip #{action["required_item_name"] || action["required_item_id"]}"

                "cooldown" ->
                  "cooldown #{max(1, div(action["cooldown_remaining_ms"], 60_000))} min"

                "select_career" ->
                  "select this career"

                _ ->
                  "ready"
              end

            "• #{action["display_name"]}: +#{action["reward_xp"]} XP / +#{action["reward_credits"]} Credits · #{status}"
          end)

        _ ->
          []
      end

    [
      if(career, do: "Current career: #{career["name"]}", else: "Choose a career to get started."),
      Enum.join(options, "\n"),
      if(activities == [], do: nil, else: "Activities:\n" <> Enum.join(activities, "\n"))
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
    |> clip()
  end

  defp format(:activities, result, _user),
    do:
      "#{String.capitalize(result["activity"])} complete · +#{result["credits"]} Credits · #{result["drop_item_id"]}" <>
        if(get_in(result, ["xp", "xp_awarded"]),
          do: " · +#{result["xp"]["xp_awarded"]} XP",
          else: ""
        )

  defp format(_, result, _user), do: "Completed: #{result["type"] || "success"}."

  defp format_shop_item(item) do
    requirement =
      cond do
        not item["eligible"] ->
          "Locked · requires level #{item["level_requirement"]}" <>
            if(item["career_requirement"],
              do: " and #{item["career_requirement"]} level #{item["career_level_requirement"]}",
              else: ""
            )

        item["state"] == "owned" ->
          "Owned ×#{item["owned_quantity"]}"

        item["state"] == "sold" ->
          "Sold out"

        true ->
          "Available"
      end

    season = if item["season"], do: "Seasonal · #{item["season"]}", else: nil

    [
      "#{rarity_mark(item["rarity"])} #{item["name"]} · #{item["rarity"]}",
      item["description"],
      "Price: #{item["unit_price"]} Credits · ID: `#{item["item_id"]}`",
      "Use /shop buy with this ID and period #{item["period_key"]}.",
      requirement,
      season
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
    |> clip()
  end

  defp format_effects(%{"effects" => []}), do: "No active consumable effects."

  defp format_effects(%{"effects" => effects}) do
    effects
    |> Enum.map(fn effect ->
      "#{effect["effect_code"]} from #{effect["source_item_id"]} · expires <t:#{div(String.to_integer(effect["expires_at_ms"]), 1000)}:R>"
    end)
    |> Enum.join("\n")
    |> clip()
  end

  defp format_listing_detail(listing) do
    expires = String.to_integer(listing["expires_at_epoch"])

    [
      "#{listing["name"]} · #{listing["rarity"]} · #{listing["category"]}",
      listing["description"],
      "Quantity: #{listing["quantity"]} · #{listing["ask_price"]} Credits",
      "Seller: `#{listing["seller_user_id"]}` · listing `#{listing["listing_id"]}`",
      "Expires <t:#{expires}:R>"
    ]
    |> Enum.reject(&is_nil/1)
    |> Enum.join("\n")
    |> clip()
  end

  defp persona_preview do
    state = Persona.snapshot(context: :general)
    phrase = Persona.render("profile.header", %{"username" => "Tori"}, state)
    {:ok, "#{phrase} · mood #{state.mood} · #{state.season} season"}
  end

  defp error_text("ITEM_NOT_OWNED"), do: "That item is not in your inventory."
  defp error_text("SELF_MARRIAGE"), do: "You cannot propose to yourself."
  defp error_text("RELATIONSHIP_CONFLICT"), do: "One of you already has an active relationship."
  defp error_text("NO_PENDING_PROPOSAL"), do: "There is no pending proposal for you."
  defp error_text("NOT_MARRIED"), do: "You do not have an active marriage."
  defp error_text("ALREADY_OWNED"), do: "You already own this unique item."
  defp error_text("MAX_STACK_REACHED"), do: "Your inventory cannot hold more of this item."
  defp error_text("READ_ONLY"), do: "Tori economy writes are currently disabled."
  defp error_text("INVALID_INPUT"), do: "Check the item ID, quantity and period."
  defp error_text("ITEM_NOT_AVAILABLE"), do: "That item or listing is no longer available."
  defp error_text("COOLDOWN_ACTIVE"), do: "That action is still on cooldown."
  defp error_text("INSUFFICIENT_FUNDS"), do: "You do not have enough Credits."

  defp error_text("REQUIREMENT_NOT_MET"),
    do: "Choose the matching career, meet its level requirement, and equip the required item."

  defp error_text(_), do: "The request could not be completed."
  defp rarity_mark("special"), do: "✦"
  defp rarity_mark("rare"), do: "◆"
  defp rarity_mark("uncommon"), do: "◇"
  defp rarity_mark(_), do: "•"
  defp clip(text) when byte_size(text) <= 1_900, do: text
  defp clip(text), do: String.slice(text, 0, 1_890) <> "…"

  defp actor_id(%{member: %{user: %{id: id}}}) when is_integer(id),
    do: {:ok, Integer.to_string(id)}

  defp actor_id(%{user: %{id: id}}) when is_integer(id), do: {:ok, Integer.to_string(id)}
  defp actor_id(_), do: {:error, :missing_actor}
end
