defmodule ToriEconomy.Shop.Rotation do
  @moduledoc "Persisted weighted drops; reads never reroll an existing period."
  alias ToriEconomy.{Catalog, Repo, Sql, WriteGate}
  alias ToriEconomy.Persona.Season

  @themes ~w(y2k cozy denim animal_print balletcore sporty summer christmas
    leopard_girly polka_dot pastel_fantasy everyday_girly sporty_sweet soft_glam)

  def current do
    seconds = Sql.query!("SELECT floor(extract(epoch from now()))::bigint").rows |> hd() |> hd()
    hours = rotation_hours()
    period_seconds = hours * 3_600
    period_key = div(seconds, period_seconds)

    case read(period_key) do
      {:ok, rotation} -> {:ok, rotation}
      :missing -> create(period_key, period_seconds)
    end
  end

  def choose(items, seed, season, count)
      when is_list(items) and is_integer(seed) and count >= 0 do
    theme = Enum.at(@themes, rem(abs(seed), length(@themes)))

    selected =
      items
      |> Enum.filter(&season_available?(&1.season, season))
      |> Enum.map(fn item ->
        weight =
          item.weight * if(theme in item.tags, do: 3, else: 1) *
            if(item.season == season, do: 3, else: 1)

        <<number::unsigned-64, _::binary>> = :crypto.hash(:sha256, "#{seed}:#{item.id}")
        unit = (number + 1) / 18_446_744_073_709_551_617
        {-:math.log(unit) / weight, item.id, item}
      end)
      |> Enum.sort_by(fn {score, id, _} -> {score, id} end)
      |> Enum.take(count)
      |> Enum.map(&elem(&1, 2))

    {theme, selected}
  end

  defp season_available?(nil, _season), do: true
  defp season_available?("winter", season) when season in [:winter, :christmas], do: true
  defp season_available?(item_season, season), do: item_season == Atom.to_string(season)

  defp create(period_key, period_seconds) do
    case WriteGate.authorize("shop.rotate") do
      :ok ->
        case Repo.transaction(fn -> create_locked(period_key, period_seconds) end) do
          {:ok, rotation} -> {:ok, rotation}
          {:error, _} -> {:error, "SERVICE_UNAVAILABLE"}
        end

      {:error, code} ->
        {:error, code}
    end
  rescue
    _ -> {:error, "SERVICE_UNAVAILABLE"}
  end

  defp create_locked(period_key, period_seconds) do
    Sql.query!("SELECT pg_advisory_xact_lock(hashtext('tori-v2-shop-rotation'))")

    case read(period_key) do
      {:ok, rotation} ->
        rotation

      :missing ->
        season = Season.current()
        seed = period_key
        {theme, items} = choose(Catalog.list_active(), seed, season, rotation_size())
        starts = period_key * period_seconds
        ends = starts + period_seconds

        Sql.query!(
          """
          INSERT INTO economy_v2_shop_rotations(period_key,theme,season,seed,starts_at,ends_at)
          VALUES ($1,$2,$3,$4,to_timestamp($5),to_timestamp($6))
          """,
          [period_key, theme, Atom.to_string(season), seed, starts, ends]
        )

        Enum.with_index(items, fn item, position ->
          stock_limit = Map.get(item.metadata, "stock_limit")

          Sql.query!(
            """
            INSERT INTO economy_v2_shop_rotation_items
              (period_key,item_id,position,unit_price,stock_limit)
            VALUES ($1,$2,$3,$4,$5)
            """,
            [period_key, item.id, position, item.buy_price, stock_limit]
          )
        end)

        {:ok, rotation} = read(period_key)
        rotation
    end
  end

  defp read(period_key) do
    case Sql.query!(
           "SELECT theme,season,extract(epoch from ends_at)::bigint FROM economy_v2_shop_rotations WHERE period_key=$1",
           [period_key]
         ).rows do
      [[theme, season, ends_at]] ->
        items =
          Sql.query!(
            """
            SELECT r.item_id,c.name,c.description,c.category,c.subcategory,c.rarity,r.unit_price,
                   r.stock_limit,r.sold,c.level_requirement,c.season,c.tags,
                   c.career_requirement,c.career_level_requirement
            FROM economy_v2_shop_rotation_items r
            JOIN economy_v2_catalog_items c ON c.item_id=r.item_id
            WHERE r.period_key=$1 ORDER BY r.position
            """,
            [period_key]
          ).rows
          |> Enum.map(fn [
                           id,
                           name,
                           description,
                           category,
                           subcategory,
                           rarity,
                           price,
                           stock_limit,
                           sold,
                           level_requirement,
                           item_season,
                           tags,
                           career_requirement,
                           career_level_requirement
                         ] ->
            %{
              "item_id" => id,
              "name" => name,
              "category" => category,
              "description" => description,
              "subcategory" => subcategory,
              "rarity" => rarity,
              "level_requirement" => level_requirement,
              "career_requirement" => career_requirement,
              "career_level_requirement" => career_level_requirement,
              "season" => item_season,
              "tags" => tags,
              "unit_price" => Integer.to_string(price),
              "remaining" => if(stock_limit, do: Integer.to_string(stock_limit - sold), else: nil)
            }
          end)

        {:ok,
         %{
           "type" => "shop_rotation",
           "period_key" => Integer.to_string(period_key),
           "theme" => theme,
           "season" => season,
           "ends_at_epoch" => Integer.to_string(ends_at),
           "items" => items
         }}

      [] ->
        :missing
    end
  end

  defp rotation_hours do
    case Integer.parse(System.get_env("TORI_SHOP_ROTATION_HOURS", "24")) do
      {hours, ""} when hours >= 1 and hours <= 168 -> hours
      _ -> raise "TORI_SHOP_ROTATION_HOURS must be between 1 and 168"
    end
  end

  defp rotation_size do
    case Integer.parse(System.get_env("TORI_SHOP_ROTATION_SIZE", "12")) do
      {size, ""} when size >= 1 and size <= 30 -> size
      _ -> raise "TORI_SHOP_ROTATION_SIZE must be between 1 and 30"
    end
  end
end
