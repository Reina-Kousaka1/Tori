defmodule ToriEconomy.Application do
  @moduledoc "Supervision boundary for the internal economy service. Disabled by default."
  use Application

  @impl true
  def start(_type, _args) do
    enabled? = System.get_env("TORI_ECONOMY_API_ENABLED") == "true"

    children =
      if enabled? do
        :ok = ToriEconomy.WriteGate.validate_startup!()
        secret = System.fetch_env!("TORI_ECONOMY_API_SECRET")
        if byte_size(secret) < 32,
          do: raise("TORI_ECONOMY_API_SECRET must have at least 32 bytes")

        port = System.get_env("TORI_ECONOMY_PORT", "4001") |> String.to_integer()
        bind = System.get_env("TORI_ECONOMY_BIND", "127.0.0.1") |> String.to_charlist()
        {:ok, ip} = :inet.parse_address(bind)

        Application.put_env(:tori_economy, ToriEconomy.Repo, database_options() ++ [pool_size: 5])

        [
          ToriEconomy.Repo,
          {Bandit, plug: ToriEconomy.Api, ip: ip, port: port}
        ]
      else
        []
      end

    Supervisor.start_link(children, strategy: :one_for_one, name: ToriEconomy.Supervisor)
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
