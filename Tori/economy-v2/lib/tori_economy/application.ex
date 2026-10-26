defmodule ToriEconomy.Application do
  @moduledoc "Supervision boundary for the internal economy service. Disabled by default."
  use Application

  alias ToriEconomy.Discord.Config, as: DiscordConfig

  @test_environment Mix.env() == :test

  @impl true
  def start(_type, _args) do
    children =
      if @test_environment do
        test_children()
      else
        if System.get_env("TORI_ECONOMY_API_ENABLED") == "true", do: api_children(), else: []
      end

    Supervisor.start_link(children, strategy: :one_for_one, name: ToriEconomy.Supervisor)
  end

  defp test_children do
    case System.get_env("TORI_ECONOMY_TEST_DATABASE_URL") do
      url when is_binary(url) and url != "" ->
        uri = URI.parse(url)
        database = uri.path && URI.decode(String.trim_leading(uri.path, "/"))

        unless uri.scheme in ["postgres", "postgresql"] and is_binary(uri.host) and
                 uri.host != "" and is_binary(database) and
                 String.ends_with?(database, "_test") and not String.contains?(database, "/") do
          raise "TORI_ECONOMY_TEST_DATABASE_URL must name an isolated PostgreSQL *_test database"
        end

        Application.put_env(:tori_economy, ToriEconomy.Repo, url: url, pool_size: 10)
        [ToriEconomy.Repo, ToriEconomy.Persona.Mood, ToriEconomy.Persona.Presence]

      _ ->
        [ToriEconomy.Persona.Mood, ToriEconomy.Persona.Presence]
    end
  end

  defp api_children do
    :ok = ToriEconomy.WriteGate.validate_startup!()
    secret = System.fetch_env!("TORI_ECONOMY_API_SECRET")

    if byte_size(secret) < 32,
      do: raise("TORI_ECONOMY_API_SECRET must have at least 32 bytes")

    port = System.get_env("TORI_ECONOMY_PORT", "4001") |> String.to_integer()
    bind = System.get_env("TORI_ECONOMY_BIND", "127.0.0.1") |> String.to_charlist()
    {:ok, ip} = :inet.parse_address(bind)

    Application.put_env(:tori_economy, ToriEconomy.Repo, database_options() ++ [pool_size: 5])

    discord_children = discord_children()

    [
      ToriEconomy.Repo,
      ToriEconomy.Persona.Mood,
      ToriEconomy.Persona.Presence,
      {Bandit, plug: ToriEconomy.Api, ip: ip, port: port}
    ] ++ automod_children() ++ discord_children
  end

  defp automod_children do
    if ToriEconomy.AutoMod.enabled?() and
         System.get_env("TORI_NOSTRUM_ENABLED") == "true" do
      [ToriEconomy.AutoMod.Supervisor]
    else
      []
    end
  end

  defp discord_children do
    case DiscordConfig.load!() do
      :disabled ->
        []

      {:enabled, %{token: token, guild_id: guild_id}} ->
        validate_discord_token!(token)
        Application.put_env(:nostrum, :token, token)
        [{ToriEconomy.Discord.Gateway, []}, {ToriEconomy.Discord.Commands, guild_id: guild_id}]
    end
  end

  defp validate_discord_token!(token) do
    try do
      :ok = Nostrum.Token.check_token!(token)
    rescue
      _ -> raise ArgumentError, "DISCORD_TOKEN is invalid"
    catch
      _, _ -> raise ArgumentError, "DISCORD_TOKEN is invalid"
    end
  end

  defp database_options do
    case System.get_env("TORI_ECONOMY_DATABASE_URL") do
      url when is_binary(url) and url != "" ->
        [url: url]

      _ ->
        [
          hostname: System.fetch_env!("TORI_ECONOMY_DATABASE_HOST"),
          port: System.get_env("TORI_ECONOMY_DATABASE_PORT", "5432") |> String.to_integer(),
          username: System.fetch_env!("TORI_ECONOMY_DATABASE_USER"),
          password: System.fetch_env!("TORI_ECONOMY_DATABASE_PASSWORD"),
          database: System.fetch_env!("TORI_ECONOMY_DATABASE_NAME")
        ]
    end
  end
end
