defmodule ToriEconomy.Discord.Commands do
  @moduledoc "Registers only the /career and /profile commands for the configured Tori guild."
  use GenServer
  require Logger

  @api "https://discord.com/api/v10"

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def definitions do
    [
      command("profile", "View a Tori economy and career profile.", [
        user_option("user", "Profile to view; defaults to you.", false)
      ]),
      command("career", "View careers, select one, or complete an activity.", career_options())
    ]
  end

  @doc false
  def register_commands(token, guild_id),
    do: register_commands(token, guild_id, &request/4)

  @doc false
  def register_commands(token, guild_id, request_fun) when is_function(request_fun, 4) do
    application_id =
      case request_fun.(:get, "#{@api}/oauth2/applications/@me", token, nil) do
        {:response, 200, application} when is_map(application) ->
          id = field(application, "id")

          if snowflake?(id) do
            id
          else
            raise "Could not identify the configured Tori bot application"
          end

        _ ->
          raise "Could not identify the configured Tori bot application"
      end

    register_guild_commands(application_id, guild_id, token, request_fun)
  end

  @impl true
  def init(opts) do
    token = Application.fetch_env!(:nostrum, :token)
    guild_id = Keyword.fetch!(opts, :guild_id)
    :ok = register_commands(token, guild_id)
    Logger.info("Registered Elixir-owned /career and /profile in the configured Tori guild")
    {:ok, %{guild_id: guild_id}}
  end

  defp register_guild_commands(application_id, guild_id, token, request_fun) do
    unless snowflake?(guild_id), do: raise("DISCORD_GUILD_ID must be a Discord server ID")

    collection =
      "#{@api}/applications/#{application_id}/guilds/#{guild_id}/commands"

    case request_fun.(:get, collection, token, nil) do
      {:response, 200, commands} when is_list(commands) ->
        Enum.each(definitions(), fn definition ->
          case Enum.find(commands, fn command ->
                 field(command, "type") == 1 and field(command, "name") == definition["name"]
               end) do
            nil ->
              ensure_success(request_fun.(:post, collection, token, definition), [200, 201])

            current ->
              id = field(current, "id")

              unless snowflake?(id), do: raise("Discord returned an invalid command ID")

              ensure_success(
                request_fun.(:patch, collection <> "/" <> id, token, definition),
                [200]
              )
          end
        end)

        :ok

      _ ->
        raise "Could not load guild commands for the configured Tori main guild"
    end
  end

  defp request(method, url, token, payload) do
    headers = [
      {~c"authorization", String.to_charlist("Bot " <> token)},
      {~c"user-agent", ~c"ToriEconomy/1.0"}
    ]

    body = if payload, do: Jason.encode!(payload), else: ""

    request =
      if payload do
        {String.to_charlist(url), headers ++ [{~c"content-type", ~c"application/json"}],
         ~c"application/json", body}
      else
        {String.to_charlist(url), headers}
      end

    http_options = [ssl: [verify: :verify_peer, cacerts: :public_key.cacerts_get()]]

    case :httpc.request(method, request, http_options, body_format: :binary) do
      {:ok, {{_version, status, _reason}, _headers, response_body}} ->
        case Jason.decode(response_body) do
          {:ok, decoded} -> {:response, status, decoded}
          {:error, _} -> {:error, :invalid_response}
        end

      _ ->
        {:error, :network}
    end
  end

  defp ensure_success({:response, status, _body}, expected) do
    if Enum.member?(expected, status) do
      :ok
    else
      raise "Discord rejected a Tori main-bot command update (HTTP #{status})"
    end
  end

  defp ensure_success(_, _), do: raise("Discord rejected a Tori main-bot command update")

  defp field(map, "name") when is_map(map), do: Map.get(map, "name", Map.get(map, :name))
  defp field(map, "id") when is_map(map), do: Map.get(map, "id", Map.get(map, :id))
  defp field(map, "type") when is_map(map), do: Map.get(map, "type", Map.get(map, :type))
  defp field(_, _), do: nil

  defp command(name, description, options \\ []),
    do: %{"name" => name, "description" => description, "type" => 1, "options" => options}

  defp sub(name, description, options \\ []),
    do: %{"name" => name, "description" => description, "type" => 1, "options" => options}

  defp career_options do
    [
      sub("status", "View career levels, progress and activities."),
      sub("select", "Select or switch your career.", [
        string("career_code", "Career", true, careers())
      ]),
      sub("activity", "Complete a cooldown-controlled career activity.", [
        string("career_code", "Your selected career", true, careers()),
        string("action_code", "Activity", true, career_activities())
      ]),
      sub("practice", "Legacy alias for your starter career practice.", [
        string("career_code", "Your selected career", true, careers())
      ])
    ]
  end

  defp career_activities,
    do:
      choices([
        {"Ballet class", "class"},
        {"Barre work", "barre"},
        {"Ballet rehearsal", "rehearsal"},
        {"Ballet performance", "performance"},
        {"Court practice", "court_practice"},
        {"Volleyball drills", "drills"},
        {"Scrimmage", "scrimmage"},
        {"Match", "match"},
        {"Cheer practice", "squad_practice"},
        {"Tumbling and stunts", "tumbling_stunts"},
        {"Routine work", "routine"},
        {"Competition", "competition"}
      ])

  defp user_option(name, description, required),
    do: %{"name" => name, "description" => description, "type" => 6, "required" => required}

  defp string(name, description, required, choices \\ nil) do
    option = %{"name" => name, "description" => description, "type" => 3, "required" => required}
    if choices, do: Map.put(option, "choices", choices), else: option
  end

  defp choices(values),
    do: Enum.map(values, fn {name, value} -> %{"name" => name, "value" => value} end)

  defp careers,
    do: choices([{"Ballet", "ballet"}, {"Volleyball", "volleyball"}, {"Cheerleading", "cheer"}])

  defp snowflake?(value) when is_binary(value), do: Regex.match?(~r/^[0-9]{17,20}$/, value)
  defp snowflake?(_), do: false
end
