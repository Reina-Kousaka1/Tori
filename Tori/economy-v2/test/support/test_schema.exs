defmodule ToriEconomy.TestSchema do
  @moduledoc false
  alias ToriEconomy.Repo
  alias Ecto.Adapters.SQL

  @migration_dir Path.expand("../../../src/main/resources/db/migration", __DIR__)

  def prepare! do
    case System.get_env("TORI_ECONOMY_TEST_DATABASE_URL") do
      nil ->
        :ok

      url ->
        ensure_target!(url)
        migrations = migrations!()

        {:ok, :ok} =
          Repo.transaction(
            fn ->
              SQL.query!(
                Repo,
                "SELECT pg_advisory_xact_lock(hashtext('tori-economy-test-schema'))",
                []
              )

              if public_tables() == [] do
                Enum.each(migrations, fn {_version, path} ->
                  SQL.query!(Repo, File.read!(path), [], query_type: :text)
                end)
              end

              verify_schema!(migrations)
            end,
            timeout: :infinity
          )

        :ok
    end
  end

  def ensure_target!(url) do
    uri = URI.parse(url)
    database = uri.path && URI.decode(String.trim_leading(uri.path, "/"))

    unless uri.scheme in ["postgres", "postgresql"] and is_binary(uri.host) and
             uri.host != "" and is_binary(database) and String.ends_with?(database, "_test") and
             not String.contains?(database, "/") do
      raise "Integration tests require an explicit PostgreSQL *_test database"
    end

    supervisor = Process.whereis(ToriEconomy.Supervisor)
    unless is_pid(supervisor), do: raise("ToriEconomy application supervisor is not running")

    pid = Process.whereis(Repo)
    unless is_pid(pid), do: raise("The application did not start its isolated test Repo")

    unless Enum.any?(Supervisor.which_children(supervisor), fn
             {Repo, child_pid, _type, _modules} -> child_pid == pid
             _ -> false
           end) do
      raise "ToriEconomy.Repo is not owned by ToriEconomy.Supervisor"
    end

    case config_mismatch(Repo.config(), uri, database) do
      nil ->
        :ok

      field ->
        raise "The application Repo differs from TORI_ECONOMY_TEST_DATABASE_URL in #{field}"
    end

    unless SQL.query!(Repo, "SELECT current_database()", []).rows == [[database]] do
      raise "The application Repo is not connected to TORI_ECONOMY_TEST_DATABASE_URL"
    end

    pid
  end

  def config_mismatch(repo_config, url) do
    uri = URI.parse(url)
    database = uri.path |> String.trim_leading("/") |> URI.decode()
    config_mismatch(repo_config, uri, database)
  end

  defp config_mismatch(repo_config, uri, database) do
    {username, password} =
      case uri.userinfo && String.split(uri.userinfo, ":", parts: 2) do
        [user, pass] -> {URI.decode(user), URI.decode(pass)}
        [user] -> {URI.decode(user), nil}
        nil -> {nil, nil}
      end

    expected = [
      scheme: uri.scheme,
      hostname: uri.host,
      port: uri.port,
      database: database,
      username: username,
      password: password
    ]

    Enum.find_value(expected, fn {field, value} ->
      if Keyword.get(repo_config, field) == value, do: nil, else: field
    end)
  end

  defp migrations! do
    migrations =
      Path.wildcard(Path.join(@migration_dir, "V*__*.sql"))
      |> Enum.map(fn path ->
        [_, version] = Regex.run(~r/^V(\d+)__.+\.sql$/, Path.basename(path))
        {String.to_integer(version), path}
      end)
      |> Enum.sort_by(&elem(&1, 0))

    versions = Enum.map(migrations, &elem(&1, 0))

    unless versions != [] and versions == Enum.to_list(1..length(versions)) do
      raise "Expected consecutive Tori Flyway SQL migrations starting at V1"
    end

    migrations
  end

  defp public_tables do
    SQL.query!(Repo, "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", []).rows
    |> Enum.map(&hd/1)
  end

  defp verify_schema!(migrations) do
    sql = Enum.map_join(migrations, "\n", fn {_version, path} -> File.read!(path) end)

    expected_tables =
      Regex.scan(~r/\bCREATE\s+TABLE\s+([a-z][a-z0-9_]*)\s*\(/i, sql, capture: :all_but_first)
      |> List.flatten()

    expected_indexes =
      Regex.scan(~r/\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+([a-z][a-z0-9_]*)\s+/i, sql,
        capture: :all_but_first
      )
      |> List.flatten()

    indexes =
      SQL.query!(Repo, "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", []).rows
      |> Enum.map(&hd/1)

    missing_tables = expected_tables -- public_tables()
    missing_indexes = expected_indexes -- indexes

    if missing_tables != [] or missing_indexes != [] do
      raise "Incomplete Tori test schema: missing tables #{inspect(missing_tables)}, indexes #{inspect(missing_indexes)}"
    end

    columns =
      SQL.query!(
        Repo,
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'economy_accounts'",
        []
      ).rows
      |> Enum.map(&hd/1)

    unless Enum.all?(["last_daily_at", "last_beg_at", "last_work_at"], &(&1 in columns)) do
      raise "Incomplete Tori test schema: economy account cooldown columns are missing"
    end

    catalog_columns =
      SQL.query!(
        Repo,
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'economy_v2_catalog_items'",
        []
      ).rows
      |> Enum.map(&hd/1)

    unless Enum.all?(
             [
               "subcategory",
               "rarity",
               "rotation_weight",
               "equip_slots",
               "max_stack",
               "cosmetic_slots",
               "career_requirement",
               "career_level_requirement"
             ],
             &(&1 in catalog_columns)
           ) do
      raise "Incomplete Tori test schema: additive catalog columns are missing"
    end

    [[catalog_count]] =
      SQL.query!(Repo, "SELECT count(*) FROM economy_v2_catalog_items WHERE active", []).rows

    unless catalog_count >= 125 do
      raise "Incomplete Tori test schema: expanded catalog migration is missing"
    end

    unless Enum.all?(
             [
               "economy_v2_career_selections",
               "economy_v2_career_actions",
               "economy_v2_cosmetic_selections"
             ],
             &(&1 in public_tables())
           ) do
      raise "Incomplete Tori test schema: progression or cosmetic selection schema is missing"
    end

    unless SQL.query!(
             Repo,
             "SELECT count(*) FROM economy_v2_catalog_items WHERE item_id='leopard_baby_tee'",
             []
           ).rows == [[1]] do
      raise "Incomplete Tori test schema: original catalog seed is missing"
    end

    unless SQL.query!(
             Repo,
             "SELECT tool_slot,durability FROM economy_v2_catalog_items WHERE item_id='fishing_rod'",
             []
           ).rows == [["rod", 40]] do
      raise "Incomplete Tori test schema: legacy activity catalog is missing or incompatible"
    end

    unless "economy_v2_active_effects" in public_tables() do
      raise "Incomplete Tori test schema: consumable effect state is missing"
    end

    :ok
  end
end
