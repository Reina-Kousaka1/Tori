defmodule ToriEconomy.RewriteDomainTest do
  use ExUnit.Case, async: false

  alias ToriEconomy.{
    Activity,
    Api,
    Consumables,
    Contract,
    Dispatcher,
    Equipment,
    Marketplace,
    Progression,
    Queries,
    Shop,
    Sql,
    TestSchema,
    WriteGate
  }

  alias ToriEconomy.Shop.Rotation

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @mutations ~w(
    activity.perform career.practice career.select daily.claim inventory.consume
    inventory.cosmetic.clear inventory.cosmetic.select inventory.equip inventory.unequip
    marketplace.buy marketplace.cancel marketplace.list progression.grant shop.purchase
    wallet.transfer
  )
  @moduletag skip: if(is_nil(@url), do: "requires explicit isolated *_test database", else: false)

  setup_all do
    TestSchema.ensure_target!(@url)
    :ok
  end

  setup do
    names = ~w(TORI_ECONOMY_WRITE_ENABLED TORI_ECONOMY_WRITE_MODE TORI_ECONOMY_DATABASE_URL)
    saved = Map.new(names, fn name -> {name, System.get_env(name)} end)
    on_exit(fn -> Enum.each(saved, fn {name, value} -> restore(name, value) end) end)
    :ok
  end

  defp snowflake,
    do: Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))

  defp request(operation, actor, args \\ %{}, interaction \\ nil) do
    context = %{
      "actor_user_id" => actor,
      "guild_id" => "234567890123456789",
      "channel_id" => "345678901234567890"
    }

    %{
      "request_id" => Ecto.UUID.generate(),
      "operation" => operation,
      "context" => context,
      "args" => args,
      "idempotency_key" =>
        if(operation in @mutations,
          do: "discord-interaction:" <> (interaction || snowflake()),
          else: nil
        )
    }
    |> then(fn raw ->
      {:ok, validated} = Contract.validate(raw)
      validated
    end)
  end

  test "weighted rotation is stable for a seed and excludes out-of-season items" do
    items = [
      %{id: "a", weight: 100, season: nil, tags: []},
      %{id: "b", weight: 5, season: "winter", tags: []},
      %{id: "c", weight: 20, season: "summer", tags: ["summer"]}
    ]

    assert Rotation.choose(items, 42, :summer, 3) == Rotation.choose(items, 42, :summer, 3)
    themes =
      Enum.map(0..30, fn seed ->
        {theme, _items} = Rotation.choose(items, seed, :summer, 3)
        theme
      end)

    assert Enum.all?(
             ~w(leopard_girly polka_dot pastel_fantasy everyday_girly sporty_sweet soft_glam),
             &(&1 in themes)
           )

    {_theme, selected} = Rotation.choose(items, 42, :summer, 3)
    refute Enum.any?(selected, &(&1.id == "b"))
  end

  test "V11 content drop adds complete style groups without replacing original identifiers" do
    assert [[count]] =
             Sql.query!("SELECT count(*) FROM economy_v2_catalog_items WHERE active").rows

    assert count >= 266

    for {category, expected_count} <- [
          {"fashion", 18},
          {"accessories", 16},
          {"beauty", 10},
          {"ballet", 10},
          {"volleyball", 10},
          {"cheer", 10},
          {"consumables", 8},
          {"collectibles", 8},
          {"seasonal", 10}
        ] do
      assert [[actual_count]] =
               Sql.query!(
                 "SELECT count(*) FROM economy_v2_catalog_items WHERE active AND 'content_drop_v1'=ANY(tags) AND category=$1",
                 [category]
               ).rows

      assert actual_count == expected_count
    end

    assert [[100, 100]] =
             Sql.query!(
               "SELECT count(*),count(DISTINCT item_id) FROM economy_v2_catalog_items WHERE 'content_drop_v1'=ANY(tags)"
             ).rows

    assert [[0]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE 'content_drop_v1'=ANY(tags) AND
                 (NOT active OR category NOT IN ('fashion','accessories','beauty','ballet','volleyball',
                   'cheer','consumables','collectibles','seasonal') OR
                  rarity NOT IN ('common','uncommon','rare','special') OR
                  buy_price IS NULL OR buy_price NOT BETWEEN 1 AND 1000000000 OR
                  sell_price IS NULL OR sell_price NOT BETWEEN 1 AND 1000000000 OR
                  sell_price <> greatest(1,buy_price/2) OR rotation_weight < 1 OR
                  max_stack IS NULL OR max_stack < 1 OR
                  (NOT stackable AND max_stack <> 1))
             """).rows

    assert [[0]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items c
               CROSS JOIN LATERAL unnest(c.equip_slots || c.conflict_slots) AS slot_values(value)
               WHERE 'content_drop_v1'=ANY(c.tags)
                 AND slot_values.value NOT IN
                   ('top','bottom','dress','outerwear','shoes','bag','accessory','jewelry','hair_accessory')
             """).rows

    assert [[0]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE 'content_drop_v1'=ANY(tags) AND
                 (('top'=ANY(equip_slots) AND NOT ('dress'=ANY(conflict_slots))) OR
                  ('bottom'=ANY(equip_slots) AND NOT ('dress'=ANY(conflict_slots))) OR
                  ('dress'=ANY(equip_slots) AND NOT
                    (conflict_slots @> ARRAY['top','bottom']::text[])))
             """).rows

    assert [[1]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE item_id='rehearsal_wrap_skirt'
                 AND equip_slots=ARRAY['bottom']::text[]
                 AND conflict_slots=ARRAY['dress']::text[]
             """).rows

    assert [[1]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE item_id='soft_lavender_cardigan'
                 AND equip_slots=ARRAY['outerwear']::text[]
                 AND cardinality(conflict_slots)=0
             """).rows

    assert [[1]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE item_id='tori_moonlit_lavender_star' AND category='collectibles'
                 AND rarity='special' AND buy_price=85000 AND NOT stackable AND max_stack=1
             """).rows

    assert [[0]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items
               WHERE 'content_drop_v1'=ANY(tags) AND category='beauty'
                 AND (cardinality(cosmetic_slots)=0 OR
                      NOT (cosmetic_slots <@ ARRAY['nails','makeup','hair_accessory']::text[]) OR
                      consumable OR stackable OR max_stack IS DISTINCT FROM 1 OR
                      cardinality(equip_slots)>0)
             """).rows

    assert [[0]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_catalog_items c
               LEFT JOIN economy_v2_consumable_effects e ON e.item_id=c.item_id AND e.active
               WHERE 'content_drop_v1'=ANY(c.tags) AND c.category='consumables'
                 AND (NOT c.consumable OR NOT c.stackable OR c.max_stack<>10 OR
                      cardinality(c.equip_slots)>0 OR cardinality(c.cosmetic_slots)>0 OR
                      e.item_id IS NULL OR e.effect_code NOT IN ('hydration_boost','focus_boost') OR
                      e.duration_ms NOT BETWEEN 1000 AND 900000)
             """).rows

    assert [[8]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_consumable_effects e
               JOIN economy_v2_catalog_items c ON c.item_id=e.item_id
               WHERE 'content_drop_v1'=ANY(c.tags) AND c.category='consumables' AND e.active
             """).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_catalog_items WHERE item_id='leopard_baby_tee'"
             ).rows

    assert [[10]] =
             Sql.query!("SELECT count(*) FROM economy_v2_consumable_effects WHERE active").rows

    assert [[6]] =
             Sql.query!("""
               SELECT count(*) FROM economy_v2_activity_effect_rules
               WHERE active AND effect_code IN ('hydration_boost','focus_boost')
             """).rows
  end

  test "V12 style collections preserve IDs, prices, tags and cosmetic boundaries" do
    assert [[51, 51]] =
             Sql.query!("""
             SELECT count(*),count(DISTINCT item_id)
             FROM economy_v2_catalog_items WHERE 'v12_style_drop'=ANY(tags)
             """).rows

    assert [[total]] = Sql.query!("SELECT count(*) FROM economy_v2_catalog_items WHERE active").rows
    assert total >= 317

    for tag <- ~w(leopard_girly young_girly polka_dot pastel_fantasy everyday_girly sporty_sweet soft_glam) do
      assert [[count]] =
               Sql.query!(
                 "SELECT count(*) FROM economy_v2_catalog_items WHERE 'v12_style_drop'=ANY(tags) AND $1=ANY(tags)",
                 [tag]
               ).rows

      assert count > 0
    end

    assert [[0]] =
             Sql.query!("""
             SELECT count(*) FROM economy_v2_catalog_items
             WHERE 'v12_style_drop'=ANY(tags) AND
               (NOT active OR rarity NOT IN ('common','uncommon','rare','special')
                OR buy_price IS NULL OR buy_price < 1 OR rotation_weight < 1
                OR max_stack <> 1 OR stackable OR consumable
                OR (category='beauty' AND
                  (cosmetic_slots<>ARRAY['nails']::text[] OR tradeable OR cardinality(equip_slots)>0)))
             """).rows

    assert [[0]] =
             Sql.query!("""
             SELECT count(*) FROM economy_v2_catalog_items c
             CROSS JOIN LATERAL unnest(c.equip_slots || c.conflict_slots) AS slot_values(value)
             WHERE 'v12_style_drop'=ANY(c.tags)
               AND slot_values.value NOT IN
                 ('top','bottom','dress','outerwear','shoes','bag','accessory',
                  'jewelry','hair_accessory','necklace','earrings')
             """).rows

    assert [[0]] =
             Sql.query!("""
             SELECT count(*) FROM economy_v2_catalog_items
             WHERE 'v12_style_drop'=ANY(tags) AND
               (('top'=ANY(equip_slots) AND NOT 'dress'=ANY(conflict_slots))
                OR ('bottom'=ANY(equip_slots) AND NOT 'dress'=ANY(conflict_slots))
                OR ('dress'=ANY(equip_slots) AND NOT conflict_slots @> ARRAY['top','bottom']::text[]))
             """).rows
  end

  test "V12 jewelry slots combine independently and profile reads the equipped style" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    for item <- ~w(everyday_white_fitted_tee cloud_parade_dress golden_layered_hearts sea_glass_drop_earrings rose_gloss_nail_style) do
      Sql.query!(
        "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,$2,1)",
        [user, item]
      )
    end

    for {item, slot} <- [
          {"everyday_white_fitted_tee", "top"},
          {"cloud_parade_dress", "dress"},
          {"golden_layered_hearts", "necklace"},
          {"sea_glass_drop_earrings", "earrings"}
        ] do
      assert {:ok, %{"status" => "ok"}} =
               Equipment.execute(request("inventory.equip", user, %{"item_id" => item, "slot" => slot}))
    end

    assert {:ok, %{"status" => "ok"}} =
             Equipment.execute(
               request("inventory.cosmetic.select", user, %{
                 "item_id" => "rose_gloss_nail_style",
                 "slot" => "nails"
               })
             )

    assert {:ok, profile} = Queries.execute(request("profile.snapshot", user))
    outfit = profile["result"]["loadout"]
    assert Enum.sort(Enum.map(outfit, & &1["slot"])) == ["dress", "earrings", "necklace"]
    assert Enum.any?(profile["result"]["cosmetics"], &(&1["slot"] == "nails"))
    refute Enum.any?(outfit, &(&1["slot"] == "top"))
  end

  test "career selection, practice, career XP, cooldown and replay are stateful and idempotent" do
    user = snowflake()
    select = request("career.select", user, %{"career_code" => "ballet"})
    assert {:ok, chosen} = Progression.execute(select)
    assert chosen["result"]["career_code"] == "ballet"
    assert {:ok, replay} = Progression.execute(%{select | request_id: Ecto.UUID.generate()})
    assert replay["result"] == chosen["result"]

    practice =
      request("career.practice", user, %{"career_code" => "ballet", "action_code" => "practice"})

    assert {:ok, earned} = Progression.execute(practice)
    assert earned["result"]["xp_awarded"] == "16"

    assert {:ok, practice_replay} =
             Progression.execute(%{practice | request_id: Ecto.UUID.generate()})

    assert practice_replay["result"] == earned["result"]

    assert [[16]] =
             Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows

    assert [[16]] =
             Sql.query!(
               "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'",
               [user]
             ).rows

    blocked =
      request("career.practice", user, %{"career_code" => "ballet", "action_code" => "practice"})

    assert {:ok, %{"error" => %{"code" => "COOLDOWN_ACTIVE"}}} = Progression.execute(blocked)

    assert [[16]] =
             Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows
  end

  test "career overview exposes the three configured four-stage gameplay loops" do
    user = snowflake()

    assert {:ok, response} = Progression.execute(request("career.snapshot", user))
    careers = Map.new(response["result"]["careers"], &{&1["code"], &1})

    assert Enum.map(careers["ballet"]["actions"], & &1["action_code"]) ==
             ~w(class barre rehearsal performance)

    assert Enum.map(careers["volleyball"]["actions"], & &1["action_code"]) ==
             ~w(court_practice drills scrimmage match)

    assert Enum.map(careers["cheer"]["actions"], & &1["action_code"]) ==
             ~w(squad_practice tumbling_stunts routine competition)

    assert Enum.at(careers["ballet"]["actions"], 2)["required_career_level"] == 3
    assert Enum.at(careers["ballet"]["actions"], 2)["required_item_id"] == "rehearsal_wrap_skirt"

    assert Enum.at(careers["volleyball"]["actions"], 2)["required_item_id"] ==
             "navy_warm_gold_court_jersey"

    assert Enum.at(careers["cheer"]["actions"], 3)["required_item_id"] ==
             "competition_day_ribbon_set"
    refute Enum.at(careers["ballet"]["actions"], 0)["available"]

    assert {:ok, _selected} =
             Progression.execute(request("career.select", user, %{"career_code" => "ballet"}))

    assert {:ok, selected_snapshot} = Progression.execute(request("career.snapshot", user))
    assert length(selected_snapshot["result"]["active_career"]["actions"]) == 4
  end

  test "career actions atomically reward XP and Credits and require level plus equipped V11 gear" do
    user = snowflake()

    assert {:ok, _} =
             Progression.execute(request("career.select", user, %{"career_code" => "ballet"}))

    Sql.query!(
      "INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',100)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'rehearsal_wrap_skirt',1),($1,'soft_knit_legwarmers',1),($1,'warmup_shrug_wrap',1)",
      [user]
    )

    rehearsal_args = %{"career_code" => "ballet", "action_code" => "rehearsal"}
    blocked_by_level = request("career.practice", user, rehearsal_args)

    assert {:ok, %{"error" => %{"code" => "REQUIREMENT_NOT_MET"}}} =
             Progression.execute(blocked_by_level)

    Sql.query!(
      "UPDATE economy_v2_career_progress SET xp=400 WHERE user_id=$1 AND career_code='ballet'",
      [user]
    )

    blocked_by_equipment = request("career.practice", user, rehearsal_args)

    assert {:ok, %{"error" => %{"code" => "REQUIREMENT_NOT_MET"}}} =
             Progression.execute(blocked_by_equipment)

    Sql.query!(
      """
        INSERT INTO economy_v2_loadout(user_id,slot,item_id)
        VALUES ($1,'bottom','rehearsal_wrap_skirt'),
               ($1,'accessory','soft_knit_legwarmers'),
               ($1,'outerwear','warmup_shrug_wrap')
      """,
      [user]
    )

    rehearsal = request("career.practice", user, rehearsal_args)
    assert {:ok, earned} = Progression.execute(rehearsal)
    assert earned["result"]["xp_awarded"] == "26"
    assert earned["result"]["career_xp"] == "426"
    assert earned["result"]["career_level"] == 3
    assert earned["result"]["credits_awarded"] == "23"
    assert earned["result"]["equipment_bonus_xp"] == 2
    assert earned["result"]["equipment_bonus_credits"] == 5

    assert {:ok, replay} =
             Progression.execute(%{rehearsal | request_id: Ecto.UUID.generate()})

    assert replay["result"] == earned["result"]

    assert [[23]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [user]).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1 AND leg='career_reward'",
               [rehearsal.idempotency_key]
             ).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_xp_events WHERE request_key=$1",
               [rehearsal.idempotency_key]
             ).rows
  end

  test "career practice does not influence Tori's global mood even when it levels the account" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_v2_account_progress(user_id,xp) VALUES ($1,99)", [user])
    Sql.query!(
      "INSERT INTO economy_v2_career_selections(user_id,career_code) VALUES ($1,'ballet')",
      [user]
    )

    before = ToriEconomy.Persona.Mood.snapshot()
    action =
      request("career.practice", user, %{"career_code" => "ballet", "action_code" => "class"})
    assert {:ok, result} = Dispatcher.execute(action)
    assert result["result"]["level_up"]
    after_state = ToriEconomy.Persona.Mood.snapshot()
    assert after_state.reason == before.reason
    assert after_state.recent_events == before.recent_events
  end

  test "career switching preserves old progression and profile reflects the new selection" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_v2_career_selections(user_id,career_code,selected_at) VALUES ($1,'ballet',now()-interval '25 hours')",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',400)",
      [user]
    )

    switch = request("career.select", user, %{"career_code" => "volleyball"})
    assert {:ok, result} = Progression.execute(switch)
    assert result["result"]["career_code"] == "volleyball"

    assert [[400]] =
             Sql.query!(
               "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'",
               [user]
             ).rows

    assert {:ok, profile} = Queries.execute(request("profile.snapshot", user))
    assert profile["result"]["progression"]["active_career"]["code"] == "volleyball"
  end

  test "career unlocks remain earned after selecting another career" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'ballet_lace_leotard',1)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',100)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_career_selections(user_id,career_code,selected_at) VALUES ($1,'volleyball',now())",
      [user]
    )

    assert {:ok, equipped} =
             Equipment.execute(
               request("inventory.equip", user, %{
                 "item_id" => "ballet_lace_leotard",
                 "slot" => "top"
               })
             )

    assert equipped["result"]["item_id"] == "ballet_lace_leotard"
  end

  test "profile snapshot reflects persisted XP, career, outfit and selected cosmetics" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,321)", [user])
    Sql.query!("INSERT INTO economy_v2_account_progress(user_id,xp) VALUES ($1,1000)", [user])

    Sql.query!(
      "INSERT INTO economy_v2_career_selections(user_id,career_code) VALUES ($1,'ballet')",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',100),($1,'volleyball',400)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_pink_slip_dress',1),($1,'soft_rose_makeup',1)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_loadout(user_id,slot,item_id) VALUES ($1,'dress','soft_pink_slip_dress')",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_v2_cosmetic_selections(user_id,slot,item_id) VALUES ($1,'makeup','soft_rose_makeup')",
      [user]
    )

    assert {:ok, response} = Queries.execute(request("profile.snapshot", user))
    profile = response["result"]
    assert profile["balance"] == "321"
    assert profile["progression"]["xp"] == "1000"
    assert profile["progression"]["level"] == 4
    assert profile["progression"]["skill_xp"] == "500"
    assert profile["progression"]["skill_level"] == 3
    assert profile["progression"]["active_career"]["code"] == "ballet"
    assert profile["progression"]["active_career"]["xp"] == "100"
    assert profile["progression"]["active_career"]["level"] == 2

    assert [%{"slot" => "dress", "name" => "Soft Pink Slip Dress"}] =
             Enum.map(profile["loadout"], &Map.take(&1, ["slot", "name"]))

    assert [%{"slot" => "makeup", "name" => "Soft Rose Makeup Style"}] =
             Enum.map(profile["cosmetics"], &Map.take(&1, ["slot", "name"]))
  end

  test "permanent cosmetic selection requires ownership and remains distinct from consumables" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_rose_makeup',1)",
      [user]
    )

    selection =
      request("inventory.cosmetic.select", user, %{
        "item_id" => "soft_rose_makeup",
        "slot" => "makeup"
      })

    assert {:ok, first} = Equipment.execute(selection)
    assert {:ok, replay} = Equipment.execute(%{selection | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [["soft_rose_makeup"]] =
             Sql.query!(
               "SELECT item_id FROM economy_v2_cosmetic_selections WHERE user_id=$1 AND slot='makeup'",
               [user]
             ).rows

    assert [[1]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='soft_rose_makeup'",
               [user]
             ).rows
  end

  test "wardrobe inventory filters by category and paginates persisted ownership" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1),($1,'pink_lace_cami',1),($1,'soft_rose_makeup',1)",
      [user]
    )

    assert {:ok, page} =
             Queries.execute(
               request("inventory.list", user, %{"category" => "fashion", "page" => 0})
             )

    assert page["result"]["total_items"] == 2
    assert Enum.all?(page["result"]["items"], &(&1["category"] == "fashion"))
    assert page["result"]["total_pages"] == 1

    assert {:ok, wardrobe} =
             Queries.execute(request("wardrobe.list", user, %{"category" => "all", "page" => 0}))

    assert wardrobe["result"]["total_items"] == 3

    assert Enum.all?(
             wardrobe["result"]["items"],
             &(&1["equip_slots"] != [] or &1["cosmetic_slots"] != [])
           )
  end

  test "new mutations are test-only and disabled mode is read-only" do
    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    System.put_env("TORI_ECONOMY_WRITE_MODE", "disabled")
    assert :ok == WriteGate.validate_startup!()
    assert {:error, "READ_ONLY"} == WriteGate.authorize("shop.purchase")
    assert {:error, "READ_ONLY"} == WriteGate.authorize("progression.grant")
    secret_before = System.get_env("TORI_ECONOMY_API_SECRET")
    on_exit(fn -> restore("TORI_ECONOMY_API_SECRET", secret_before) end)
    System.put_env("TORI_ECONOMY_API_SECRET", String.duplicate("t", 32))
    req = request("progression.grant", snowflake(), %{"source_code" => "activity_test"})

    response =
      Plug.Test.conn(
        :post,
        "/internal/economy/v1/execute",
        Jason.encode!(%{
          "request_id" => req.request_id,
          "idempotency_key" => req.idempotency_key,
          "operation" => req.operation,
          "context" => req.context,
          "args" => req.args
        })
      )
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> String.duplicate("t", 32))
      |> Plug.Conn.put_req_header("content-type", "application/json")
      |> Api.call([])

    assert response.status == 403
    assert Jason.decode!(response.resp_body)["error"]["code"] == "READ_ONLY"
    System.put_env("TORI_ECONOMY_WRITE_MODE", "test")
    System.put_env("TORI_ECONOMY_DATABASE_URL", @url)
    assert :ok == WriteGate.authorize("shop.purchase")
  end

  test "shop purchase is atomic and interaction replay cannot double charge" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,10000)", [user])
    # Rotation creation is an explicit test write, persisted once for this period.
    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    System.put_env("TORI_ECONOMY_WRITE_MODE", "test")
    System.put_env("TORI_ECONOMY_DATABASE_URL", @url)
    {:ok, rotation} = Rotation.current()

    item =
      Enum.find(rotation["items"], fn item ->
        is_nil(item["remaining"]) and item["level_requirement"] == 1 and
          is_nil(item["career_requirement"])
      end)

    assert item

    args = %{
      "item_id" => item["item_id"],
      "quantity" => 1,
      "period_key" => rotation["period_key"]
    }

    first_request = request("shop.purchase", user, args)
    assert {:ok, first} = Shop.execute(first_request)
    assert first["status"] == "ok"
    assert {:ok, replay} = Shop.execute(%{first_request | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1",
               [first_request.idempotency_key]
             ).rows

    browse = request("shop.rotation", user, %{"category" => "all", "page" => 0})
    assert {:ok, page} = Shop.execute(browse)
    assert page["result"]["total_items"] >= 12

    assert Enum.any?(
             page["result"]["items"],
             &(&1["state"] in ["available", "owned", "locked", "sold"])
           )

    assert {:ok, details} =
             Shop.execute(
               request("shop.item", user, %{
                 "item_id" => item["item_id"],
                 "period_key" => rotation["period_key"]
               })
             )

    assert details["result"]["name"] == item["name"]
  end

  test "full catalog purchase respects credits and unique ownership" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,50)", [user])

    assert {:ok, catalog} =
             Shop.execute(request("shop.styles", user, %{"category" => "fashion", "page" => 0}))

    assert catalog["result"]["total_items"] > 20
    assert catalog["result"]["type"] == "shop_catalog"

    item_id = "soft_lavender_cardigan"
    args = %{"item_id" => item_id, "quantity" => 1, "period_key" => "catalog"}

    assert {:ok, insufficient} = Shop.execute(request("shop.purchase", user, args))
    assert insufficient["error"]["code"] == "INSUFFICIENT_FUNDS"
    assert [] == Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1", [user]).rows

    Sql.query!("UPDATE economy_accounts SET balance=500 WHERE user_id=$1", [user])
    assert {:ok, purchase} = Shop.execute(request("shop.purchase", user, args))
    assert purchase["result"]["balance"] == "290"

    assert {:ok, details} =
             Shop.execute(
               request("shop.item", user, %{"item_id" => item_id, "period_key" => "catalog"})
             )

    assert details["result"]["name"] == "Soft Lavender Cardigan"

    unique = %{"item_id" => "soft_lilac_nail_lacquer", "quantity" => 2, "period_key" => "catalog"}
    assert {:ok, rejected} = Shop.execute(request("shop.purchase", user, unique))
    assert rejected["error"]["code"] == "ALREADY_OWNED"
  end

  test "configured career XP is idempotent and does not invent a threshold" do
    user = snowflake()
    source = "test_" <> snowflake()

    Sql.query!(
      "INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active) VALUES ($1,7,60000,'ballet',true)",
      [source]
    )

    req = request("progression.grant", user, %{"source_code" => source})
    assert {:ok, first} = Progression.execute(req)
    assert first["result"]["xp"] == "7"

    assert [[7]] =
             Sql.query!(
               "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'",
               [user]
             ).rows

    assert {:ok, replay} = Progression.execute(%{req | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert {:ok, cooldown} =
             Progression.execute(request("progression.grant", user, %{"source_code" => source}))

    assert cooldown["error"]["code"] == "COOLDOWN_ACTIVE"
  end

  test "marketplace listing escrows an owned item and replay preserves quantity" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1)",
      [user]
    )

    req =
      request("marketplace.list", user, %{
        "item_id" => "leopard_baby_tee",
        "quantity" => 1,
        "ask_price" => "90",
        "expires_hours" => 24
      })

    assert {:ok, first} = Marketplace.execute(req)
    assert first["status"] == "ok"
    assert {:ok, replay} = Marketplace.execute(%{req | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [] ==
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='leopard_baby_tee'",
               [user]
             ).rows
  end

  test "marketplace purchase transfers item and credits exactly once" do
    seller = snowflake()
    buyer = snowflake()

    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,0),($2,500)", [
      seller,
      buyer
    ])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'pink_lace_cami',1)",
      [seller]
    )

    listing =
      request("marketplace.list", seller, %{
        "item_id" => "pink_lace_cami",
        "quantity" => 1,
        "ask_price" => "90",
        "expires_hours" => 24
      })

    assert {:ok, %{"result" => %{"listing_id" => id}}} = Marketplace.execute(listing)

    assert {:ok, browse} =
             Marketplace.execute(
               request("marketplace.browse", buyer, %{"category" => "fashion", "page" => 0})
             )

    assert browse["result"]["total_items"] == 1
    assert hd(browse["result"]["listings"])["listing_id"] == id

    assert {:ok, detail} =
             Marketplace.execute(request("marketplace.inspect", buyer, %{"listing_id" => id}))

    assert detail["result"]["seller_user_id"] == seller
    assert detail["result"]["ask_price"] == "90"
    purchase = request("marketplace.buy", buyer, %{"listing_id" => id})
    assert {:ok, first} = Marketplace.execute(purchase)
    assert first["status"] == "ok"
    assert {:ok, replay} = Marketplace.execute(%{purchase | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [[90]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [seller]).rows

    assert [[410]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [buyer]).rows

    assert [[1]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='pink_lace_cami'",
               [buyer]
             ).rows

    assert [[2]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1",
               [purchase.idempotency_key]
             ).rows
  end

  test "seller can reclaim an expired escrow and loadout requires ownership" do
    seller = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [seller])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_pink_slip_dress',1)",
      [seller]
    )

    equip =
      request("inventory.equip", seller, %{"item_id" => "soft_pink_slip_dress", "slot" => "dress"})

    assert {:ok, %{"status" => "ok"}} = Equipment.execute(equip)

    listing =
      request("marketplace.list", seller, %{
        "item_id" => "soft_pink_slip_dress",
        "quantity" => 1,
        "ask_price" => "100",
        "expires_hours" => 1
      })

    assert {:ok, %{"result" => %{"listing_id" => id}}} = Marketplace.execute(listing)
    assert [] = Sql.query!("SELECT slot FROM economy_v2_loadout WHERE user_id=$1", [seller]).rows

    Sql.query!(
      "UPDATE economy_v2_marketplace_listings SET expires_at=now()-interval '1 second' WHERE listing_id=$1",
      [id]
    )

    cancel = request("marketplace.cancel", seller, %{"listing_id" => id})
    assert {:ok, %{"status" => "ok"}} = Marketplace.execute(cancel)

    assert [[1]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='soft_pink_slip_dress'",
               [seller]
             ).rows
  end

  test "wardrobe enforces dress slot conflicts and inventory inspection uses owned catalog data" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1),($1,'soft_pink_slip_dress',1)",
      [user]
    )

    assert {:ok, _} =
             Equipment.execute(
               request("inventory.equip", user, %{
                 "item_id" => "leopard_baby_tee",
                 "slot" => "top"
               })
             )

    assert {:ok, _} =
             Equipment.execute(
               request("inventory.equip", user, %{
                 "item_id" => "soft_pink_slip_dress",
                 "slot" => "dress"
               })
             )

    assert [["dress", "soft_pink_slip_dress"]] =
             Sql.query!("SELECT slot,item_id FROM economy_v2_loadout WHERE user_id=$1", [user]).rows

    assert {:ok, detail} =
             Queries.execute(
               request("inventory.item", user, %{"item_id" => "soft_pink_slip_dress"})
             )

    assert detail["result"]["description"] == "A simple glossy evening look."
    assert detail["result"]["equip_slots"] == ["dress"]
  end

  test "gather uses existing tool, wallet, inventory and cooldown atomically" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'fishing_rod',1)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')",
      [user]
    )

    req = request("activity.perform", user, %{"activity" => "fish"})
    # Two controlled rolls: lowest credit reward and first fish-crate threshold.
    assert {:ok, first} = Activity.execute(req, fn _ -> 1 end)
    assert first["result"]["credits"] == "10"
    assert first["result"]["drop_item_id"] == "fish_crate"

    assert {:ok, replay} =
             Activity.execute(%{req | request_id: Ecto.UUID.generate()}, fn _ -> 100 end)

    assert replay["result"] == first["result"]

    assert {:ok, cooldown} =
             Activity.execute(request("activity.perform", user, %{"activity" => "fish"}))

    assert cooldown["error"]["code"] == "COOLDOWN_ACTIVE"

    assert [[10]] =
             Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [user]).rows

    assert [[1]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='fish_crate'",
               [user]
             ).rows

    assert [[1]] =
             Sql.query!("SELECT count(*) FROM economy_v2_activity_events WHERE request_key=$1", [
               req.idempotency_key
             ]).rows
  end

  test "configured activity XP commits with the gather result" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'axe',1)", [
      user
    ])

    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'axe','axe')", [
      user
    ])

    Sql.query!("""
      INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active)
      VALUES ('activity_chop',7,60000,NULL,true)
      ON CONFLICT (source_code) DO UPDATE SET reward_xp=7,cooldown_ms=60000,career_code=NULL,active=true
    """)

    req = request("activity.perform", user, %{"activity" => "chop"})
    assert {:ok, result} = Activity.execute(req, fn _ -> 1 end)
    assert result["result"]["xp"]["xp_awarded"] == "7"

    assert [[7]] =
             Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows

    assert [[1]] =
             Sql.query!("SELECT count(*) FROM economy_v2_xp_events WHERE request_key=$1", [
               req.idempotency_key
             ]).rows
  end

  test "configured consumable use is persistent and replay-safe" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'practice_water',2)",
      [user]
    )

    Sql.query!("""
      INSERT INTO economy_v2_consumable_effects(item_id,effect_code,duration_ms,active)
      VALUES ('practice_water','test_refresh',60000,true)
      ON CONFLICT (item_id) DO UPDATE SET effect_code='test_refresh',duration_ms=60000,active=true
    """)

    req = request("inventory.consume", user, %{"item_id" => "practice_water"})
    assert {:ok, first} = Consumables.execute(req)
    assert first["result"]["effect_code"] == "test_refresh"
    assert {:ok, replay} = Consumables.execute(%{req | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]

    assert [[1]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='practice_water'",
               [user]
             ).rows

    assert [[1]] =
             Sql.query!(
               "SELECT count(*) FROM economy_v2_active_effects WHERE user_id=$1 AND effect_code='test_refresh'",
               [user]
             ).rows

    assert {:ok, inspected} =
             Queries.execute(request("inventory.item", user, %{"item_id" => "practice_water"}))

    assert inspected["result"]["effect_code"] == "test_refresh"
    assert inspected["result"]["effect_duration_ms"] == 60_000
    assert inspected["result"]["effect_active"]
    assert {:ok, effects} = Consumables.execute(request("inventory.effects", user))
    assert Enum.any?(effects["result"]["effects"], &(&1["effect_code"] == "test_refresh"))

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'fishing_rod',1)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')",
      [user]
    )

    Sql.query!("""
      INSERT INTO economy_v2_activity_effect_rules(effect_code,activity,credit_bonus_percent,active)
      VALUES ('test_refresh','fish',50,true)
      ON CONFLICT (effect_code,activity) DO UPDATE SET credit_bonus_percent=50,active=true
    """)

    assert {:ok, gather} =
             Activity.execute(request("activity.perform", user, %{"activity" => "fish"}), fn _ ->
               1
             end)

    assert gather["result"]["credits"] == "15"
  end

  test "starter consumable effects apply once through the gated activity domain" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      "INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'practice_water',1),($1,'fishing_rod',1)",
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')",
      [user]
    )

    consume = request("inventory.consume", user, %{"item_id" => "practice_water"})
    assert {:ok, used} = Consumables.execute(consume)
    assert used["result"]["effect_code"] == "hydration_boost"
    assert {:ok, replay} = Consumables.execute(%{consume | request_id: Ecto.UUID.generate()})
    assert replay["result"] == used["result"]

    assert [[0]] =
             Sql.query!(
               "SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='practice_water'",
               [user]
             ).rows

    gather = request("activity.perform", user, %{"activity" => "fish"})
    assert {:ok, result} = Activity.execute(gather, fn _ -> 1 end)
    assert result["result"]["credits"] == "11"
  end

  test "V11 smoothie uses the existing short-lived bounded activity effect" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])

    Sql.query!(
      """
      INSERT INTO economy_inventory(user_id,item_id,quantity)
      VALUES ($1,'berry_hydration_smoothie',1),($1,'fishing_rod',1)
      """,
      [user]
    )

    Sql.query!(
      "INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')",
      [user]
    )

    assert [[900_000]] =
             Sql.query!("""
               SELECT duration_ms FROM economy_v2_consumable_effects
               WHERE item_id='berry_hydration_smoothie' AND active
             """).rows

    consume = request("inventory.consume", user, %{"item_id" => "berry_hydration_smoothie"})
    assert {:ok, used} = Consumables.execute(consume)
    assert used["result"]["effect_code"] == "hydration_boost"

    gather = request("activity.perform", user, %{"activity" => "fish"})
    assert {:ok, result} = Activity.execute(gather, fn _ -> 1 end)
    assert result["result"]["credits"] == "11"
  end

  defp restore(name, nil), do: System.delete_env(name)
  defp restore(name, value), do: System.put_env(name, value)
end
