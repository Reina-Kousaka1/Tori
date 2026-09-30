defmodule ToriEconomy.Discord.PreviewCommands do
  @moduledoc "Registers only the uniquely named preview commands in one explicit test guild."
  use GenServer
  alias ToriEconomy.Discord.Adapter

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def definitions do
    [
      command("tori-profile-preview", "Preview your persisted Tori profile."),
      command("tori-persona-preview", "Preview Tori's current mood and season."),
      command("tori-shop-preview", "Browse and purchase from the test-gated shop preview.", [
        sub("browse", "Browse the current persisted drop.", [
          string("category", "Catalog category", false, categories()),
          integer("page", "Page number starting at zero", false, 0, 1000)
        ]),
        sub("item", "Inspect an item in the active drop.", [
          string("item_id", "Stable item ID", true), string("period_key", "Drop period key", true)
        ]),
        sub("buy", "Purchase from the active drop.", [
          string("item_id", "Stable item ID", true), string("period_key", "Drop period key", true),
          integer("quantity", "Quantity", false, 1, 100)
        ])
      ]),
      command("tori-wardrobe-preview", "View and manage owned fashion in the test preview.", [
        sub("list", "View owned clothing and cosmetics.", [
          string("category", "Filter by catalog category", false, categories()),
          integer("page", "Page number starting at zero", false, 0, 1000)
        ]),
        sub("inspect", "Inspect an owned item.", [string("item_id", "Owned item ID", true)]),
        sub("equip", "Equip owned clothing.", [
          string("item_id", "Owned catalog item", true), string("slot", "Fashion slot", true, fashion_slots())
        ]),
        sub("unequip", "Clear a fashion slot.", [string("slot", "Fashion slot", true, fashion_slots())]),
        sub("cosmetic", "Select an owned permanent cosmetic.", [
          string("item_id", "Owned cosmetic item", true), string("slot", "Cosmetic slot", true, cosmetic_slots())
        ]),
        sub("clear", "Clear a selected cosmetic.", [string("slot", "Cosmetic slot", true, cosmetic_slots())])
      ]),
      command("tori-marketplace-preview", "Browse and manage test-gated escrow listings.", [
        sub("browse", "Browse active listings.", [
          string("category", "Item category", false, categories()),
          integer("page", "Page number starting at zero", false, 0, 1000)
        ]),
        sub("inspect", "Inspect an active listing.", [string("listing_id", "Listing ID", true)]),
        sub("list", "Escrow an owned tradeable item for sale.", [
          string("item_id", "Stable item ID", true), integer("quantity", "Quantity", true, 1, 100),
          integer("ask_price", "Total asking price in Credits", true, 1, 9_000_000_000_000_000),
          integer("expires_hours", "Listing duration", false, 1, 168)
        ]),
        sub("buy", "Buy an active listing.", [string("listing_id", "Listing ID", true)]),
        sub("cancel", "Cancel and reclaim your own listing.", [string("listing_id", "Listing ID", true)])
      ]),
      command("tori-career-preview", "View or progress a persisted career.", [
        sub("status", "View career levels and current selection."),
        sub("select", "Select or switch your career.", [string("career_code", "Career", true, careers())]),
        sub("practice", "Complete one cooldown-controlled practice action.", [string("career_code", "Selected career", true, careers())])
      ]),
      command("tori-consumable-preview", "Use a configured owned consumable.", [
        sub("effects", "View your current active effects."),
        sub("inspect", "Inspect a consumable and its configured effect.", [string("item_id", "Owned item ID", true)]),
        sub("use", "Consume one item and activate its configured effect.", [string("item_id", "Consumable item ID", true)])
      ]),
      command("tori-activity-preview", "Try an isolated test-gated gathering activity.", [
        sub("perform", "Perform fish, mine or chop.", [
          string(
            "activity",
            "Activity",
            true,
            choices([{"Fishing", "fish"}, {"Mining", "mine"}, {"Chopping", "chop"}])
          )
        ])
      ])
    ]
  end

  def safe_test_database?(mode, configured_name, connected_name) do
    mode == "test" and is_binary(configured_name) and is_binary(connected_name) and
      String.ends_with?(configured_name, "_test") and configured_name == connected_name
  end

  @impl true
  def init(_opts) do
    unless System.get_env("TORI_NOSTRUM_PREVIEW_ONLY") == "true" do
      raise "Nostrum may start only with TORI_NOSTRUM_PREVIEW_ONLY=true"
    end

    application_id = snowflake_env!("TORI_NOSTRUM_APPLICATION_ID")
    guild_id = snowflake_env!("TORI_NOSTRUM_PREVIEW_GUILD_ID")
    token = System.fetch_env!("TORI_NOSTRUM_TOKEN")
    if byte_size(token) < 40, do: raise("TORI_NOSTRUM_TOKEN is invalid")

    configured_database = configured_database(ToriEconomy.Repo.config())
    [[connected_database]] = Ecto.Adapters.SQL.query!(ToriEconomy.Repo,
      "SELECT current_database()", []).rows
    unless safe_test_database?(System.get_env("TORI_ECONOMY_WRITE_MODE"),
      configured_database, connected_database) do
      raise "Nostrum preview requires the connected PostgreSQL database to be the configured *_test database"
    end

    :ok = register_guild_commands(application_id, guild_id, token)
    {:ok, %{application_id: application_id, guild_id: guild_id}}
  end

  defp register_guild_commands(application_id, guild_id, token) do
    collection = "https://discord.com/api/v10/applications/#{application_id}/guilds/#{guild_id}/commands"
    case request(:get, collection, token, nil) do
      {:response, 200, commands} when is_list(commands) ->
      Enum.each(definitions(), fn definition ->
        case Enum.find(commands, &(field(&1, "name") == definition["name"])) do
          nil -> ensure_success(request(:post, collection, token, definition), [200, 201])
          current ->
            id = field(current, "id")
            ensure_success(request(:put, collection <> "/" <> id, token, definition), [200])
        end
      end)
      :ok
      _ -> raise "Could not load guild commands for the configured Tori preview application"
    end
  end

  defp request(method, url, token, payload) do
    headers = [{~c"authorization", String.to_charlist("Bot " <> token)},
               {~c"user-agent", ~c"ToriEconomyPreview/1.0"}]
    body = if payload, do: Jason.encode!(payload), else: ""
    request = if payload do
      {String.to_charlist(url), headers ++ [{~c"content-type", ~c"application/json"}],
       ~c"application/json", body}
    else
      {String.to_charlist(url), headers}
    end
    http_options = [ssl: [verify: :verify_peer, cacerts: :public_key.cacerts_get()]]

    case :httpc.request(method, request, http_options, body_format: :binary) do
      {:ok, {{_version, status, _reason}, _headers, response_body}} ->
        decoded = if response_body == "", do: nil, else: Jason.decode!(response_body)
        {:response, status, decoded}
      _ -> {:error, :network}
    end
  end

  defp ensure_success({:response, status, _body}, expected) when status in expected, do: :ok
  defp ensure_success(_, _), do: raise("Discord rejected a Tori preview command update")
  defp field(map, "name"), do: Map.get(map, "name", Map.get(map, :name))
  defp field(map, "id"), do: Map.get(map, "id", Map.get(map, :id))

  defp command(name, description, options \\ []),
    do: %{"name" => name, "description" => description, "type" => 1, "options" => options}
  defp sub(name, description, options \\ []),
    do: %{"name" => name, "description" => description, "type" => 1, "options" => options}
  defp string(name, description, required, choices \\ nil) do
    option = %{"name" => name, "description" => description, "type" => 3, "required" => required}
    if choices, do: Map.put(option, "choices", choices), else: option
  end
  defp integer(name, description, required, min \\ nil, max \\ nil) do
    option = %{"name" => name, "description" => description, "type" => 4, "required" => required}
    option = if min, do: Map.put(option, "min_value", min), else: option
    if max, do: Map.put(option, "max_value", max), else: option
  end
  defp choices(values), do: Enum.map(values, fn {name, value} -> %{"name" => name, "value" => value} end)
  defp categories, do: choices(Adapter.shop_categories())
  defp fashion_slots, do: choices(Enum.map(~w(top bottom dress outerwear shoes bag accessory jewelry hair_accessory),
    &{String.replace(&1, "_", " "), &1}))
  defp cosmetic_slots, do: choices(Enum.map(~w(nails makeup hair_accessory),
    &{String.replace(&1, "_", " "), &1}))
  defp careers, do: choices([{"Ballet", "ballet"}, {"Volleyball", "volleyball"}, {"Cheerleading", "cheer"}])

  defp snowflake_env!(name) do
    value = System.fetch_env!(name)
    unless Regex.match?(~r/^[0-9]{17,20}$/, value), do: raise("#{name} must be a Discord Snowflake ID")
    value
  end

  def configured_database(config) do
    case Keyword.get(config, :database) do
      name when is_binary(name) -> name
      _ ->
        case URI.parse(Keyword.get(config, :url, "")) do
          %URI{scheme: scheme, path: path} when scheme in ["postgres", "postgresql"] and is_binary(path) ->
            URI.decode(String.trim_leading(path, "/"))
          _ -> nil
        end
    end
  end
end
