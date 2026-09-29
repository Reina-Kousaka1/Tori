defmodule ToriEconomy.WriteGate do
  @moduledoc "Keeps experimental economy writes restricted to an explicit test database."

  def writes_enabled? do
    System.get_env("TORI_ECONOMY_WRITE_ENABLED") == "true" and test_database?()
  end

  def validate_startup! do
    if System.get_env("TORI_ECONOMY_WRITE_ENABLED") == "true" and not test_database?() do
      raise "TORI_ECONOMY_WRITE_ENABLED=true requires TORI_ECONOMY_DATABASE_URL to name a PostgreSQL *_test database"
    end

    :ok
  end

  defp test_database? do
    case URI.parse(System.get_env("TORI_ECONOMY_DATABASE_URL", "")) do
      %URI{scheme: scheme, host: host, path: path}
      when scheme in ["postgres", "postgresql"] and is_binary(host) and is_binary(path) ->
        String.ends_with?(path, "_test")

      _ ->
        false
    end
  end
end
