defmodule ToriEconomy.WriteGate do
  @moduledoc "Requires explicit test or production cutover approval for economy writes."
  alias ToriEconomy.Repo
  alias Ecto.Adapters.SQL

  @production_ack "I_VERIFIED_BACKUP_RESTORE_SCHEMA_AND_EXCLUSIVE_WRITER_OWNERSHIP"
  @mutations ["daily.claim", "wallet.transfer"]

  def authorize(operation) when operation in @mutations do
    if System.get_env("TORI_ECONOMY_WRITE_ENABLED") == "true" and mode_allows?(operation) do
      verify_connected_database()
    else
      {:error, "READ_ONLY"}
    end
  end

  def authorize(_), do: {:error, "READ_ONLY"}

  def validate_startup! do
    if System.get_env("TORI_ECONOMY_WRITE_ENABLED") == "true" do
      case write_mode() do
        "disabled" ->
          :ok

        mode when mode in ["", "test"] ->
          unless test_database?() do
            raise "Economy test writes require a PostgreSQL *_test database"
          end

        "production" ->
          unless production_cutover_valid?() do
            raise "Production economy writes require explicit cutover acknowledgement, database name, and operation allowlist"
          end

        _ ->
          raise "TORI_ECONOMY_WRITE_MODE must be disabled, test, or production"
      end
    end

    :ok
  end

  defp mode_allows?(operation) do
    case write_mode() do
      "disabled" -> false
      "test" -> test_database?()
      "" -> test_database?()
      "production" ->
        production_cutover_valid?() and operation in production_operations()

      _ -> false
    end
  end

  defp write_mode, do: System.get_env("TORI_ECONOMY_WRITE_MODE", "") |> String.downcase()

  defp test_database? do
    database_name = configured_database_name()

    configured_postgres?() and is_binary(database_name) and String.ends_with?(database_name, "_test")
  end

  defp production_cutover_valid? do
    expected_name = System.get_env("TORI_ECONOMY_PRODUCTION_DATABASE_NAME")
    database_name = configured_database_name()

    System.get_env("TORI_ECONOMY_PRODUCTION_CUTOVER_ACK") == @production_ack and
      configured_postgres?() and is_binary(expected_name) and expected_name != "" and
      not String.ends_with?(expected_name, "_test") and database_name == expected_name and
      production_operations() != []
  end

  defp production_operations do
    System.get_env("TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS", "")
    |> String.split(",", trim: true)
    |> Enum.map(&String.trim/1)
    |> Enum.uniq()
    |> Enum.filter(&(&1 in @mutations))
  end

  defp configured_postgres? do
    case nonblank(System.get_env("TORI_ECONOMY_DATABASE_URL")) do
      nil ->
        is_binary(nonblank(System.get_env("TORI_ECONOMY_DATABASE_HOST"))) and
          is_binary(nonblank(configured_database_name()))

      url ->
        case URI.parse(url) do
          %URI{scheme: scheme, host: host, path: path}
          when scheme in ["postgres", "postgresql"] and is_binary(host) and is_binary(path) ->
            URI.decode(String.trim_leading(path, "/")) == configured_database_name()

          _ -> false
        end
    end
  end

  defp configured_database_name do
    case nonblank(System.get_env("TORI_ECONOMY_DATABASE_NAME")) do
      nil ->
        case URI.parse(System.get_env("TORI_ECONOMY_DATABASE_URL", "")) do
          %URI{path: path} when is_binary(path) -> URI.decode(String.trim_leading(path, "/"))
          _ -> nil
        end

      name -> name
    end
  end

  defp verify_connected_database do
    case write_mode() do
      "production" -> verify_production_database()
      mode when mode in ["", "test"] -> verify_test_database()
      _ -> {:error, "READ_ONLY"}
    end
  rescue
    _ -> {:error, "SERVICE_UNAVAILABLE"}
  catch
    :exit, _ -> {:error, "SERVICE_UNAVAILABLE"}
  end

  defp verify_test_database do
    case SQL.query(Repo, "SELECT current_database(), to_regclass('public.economy_v2_requests') IS NOT NULL, to_regclass('public.economy_v2_ledger_entries') IS NOT NULL", []) do
      {:ok, %{rows: [[database_name, true, true]]}} when is_binary(database_name) ->
        if String.ends_with?(database_name, "_test"), do: :ok, else: {:error, "READ_ONLY"}

      {:ok, _} -> {:error, "READ_ONLY"}
      {:error, _} -> {:error, "SERVICE_UNAVAILABLE"}
    end
  end

  defp verify_production_database do
    expected_name = System.get_env("TORI_ECONOMY_PRODUCTION_DATABASE_NAME")

    case SQL.query(
           Repo,
           """
           SELECT current_database(),
                  to_regclass('public.economy_v2_requests') IS NOT NULL,
                  to_regclass('public.economy_v2_ledger_entries') IS NOT NULL,
                  EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '5' AND success)
           """,
           []
         ) do
      {:ok, %{rows: [[^expected_name, true, true, true]]}} -> :ok
      {:ok, _} -> {:error, "READ_ONLY"}
      {:error, _} -> {:error, "SERVICE_UNAVAILABLE"}
    end
  end

  defp nonblank(value) when is_binary(value) do
    value = String.trim(value)
    if value == "", do: nil, else: value
  end

  defp nonblank(_), do: nil
end
