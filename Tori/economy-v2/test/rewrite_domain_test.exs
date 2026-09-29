defmodule ToriEconomy.RewriteDomainTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Activity, Api, Consumables, Contract, Equipment, Marketplace, Progression, Queries, Shop, Sql, TestSchema, WriteGate}
  alias ToriEconomy.Shop.Rotation

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
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

  defp snowflake, do: Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))
  defp request(operation, actor, args \\ %{}, interaction \\ nil) do
    context = %{"actor_user_id" => actor, "guild_id" => "234567890123456789",
                "channel_id" => "345678901234567890"}
    %{"request_id" => Ecto.UUID.generate(), "operation" => operation, "context" => context,
      "args" => args, "idempotency_key" => "discord-interaction:" <> (interaction || snowflake())}
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
    {_theme, selected} = Rotation.choose(items, 42, :summer, 3)
    refute Enum.any?(selected, &(&1.id == "b"))
  end

  test "V10 catalog expands style categories without replacing original identifiers" do
    assert [[count]] = Sql.query!("SELECT count(*) FROM economy_v2_catalog_items WHERE active").rows
    assert count >= 125

    for category <- ~w(fashion accessories beauty ballet volleyball cheer consumables collectibles seasonal) do
      assert [[true]] = Sql.query!("SELECT count(*)>0 FROM economy_v2_catalog_items WHERE active AND category=$1", [category]).rows
    end

    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_catalog_items WHERE item_id='leopard_baby_tee'").rows
    assert [[2]] = Sql.query!("SELECT count(*) FROM economy_v2_consumable_effects WHERE active").rows
    assert [[6]] = Sql.query!("SELECT count(*) FROM economy_v2_activity_effect_rules WHERE active").rows
  end

  test "career selection, practice, career XP, cooldown and replay are stateful and idempotent" do
    user = snowflake()
    select = request("career.select", user, %{"career_code" => "ballet"})
    assert {:ok, chosen} = Progression.execute(select)
    assert chosen["result"]["career_code"] == "ballet"
    assert {:ok, replay} = Progression.execute(%{select | request_id: Ecto.UUID.generate()})
    assert replay["result"] == chosen["result"]

    practice = request("career.practice", user, %{"career_code" => "ballet", "action_code" => "practice"})
    assert {:ok, earned} = Progression.execute(practice)
    assert earned["result"]["xp_awarded"] == "16"
    assert {:ok, practice_replay} = Progression.execute(%{practice | request_id: Ecto.UUID.generate()})
    assert practice_replay["result"] == earned["result"]
    assert [[16]] = Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows
    assert [[16]] = Sql.query!("SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'", [user]).rows

    blocked = request("career.practice", user, %{"career_code" => "ballet", "action_code" => "practice"})
    assert {:ok, %{"error" => %{"code" => "COOLDOWN_ACTIVE"}}} = Progression.execute(blocked)
    assert [[16]] = Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows
  end

  test "career switching preserves old progression and profile reflects the new selection" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_v2_career_selections(user_id,career_code,selected_at) VALUES ($1,'ballet',now()-interval '25 hours')", [user])
    Sql.query!("INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',400)", [user])

    switch = request("career.select", user, %{"career_code" => "volleyball"})
    assert {:ok, result} = Progression.execute(switch)
    assert result["result"]["career_code"] == "volleyball"
    assert [[400]] = Sql.query!("SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'", [user]).rows
    assert {:ok, profile} = Queries.execute(request("profile.snapshot", user))
    assert profile["result"]["progression"]["active_career"]["code"] == "volleyball"
  end

  test "career unlocks remain earned after selecting another career" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'ballet_lace_leotard',1)", [user])
    Sql.query!("INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',100)", [user])
    Sql.query!("INSERT INTO economy_v2_career_selections(user_id,career_code,selected_at) VALUES ($1,'volleyball',now())", [user])

    assert {:ok, equipped} = Equipment.execute(request("inventory.equip", user,
      %{"item_id" => "ballet_lace_leotard", "slot" => "top"}))
    assert equipped["result"]["item_id"] == "ballet_lace_leotard"
  end

  test "profile snapshot reflects persisted XP, career, outfit and selected cosmetics" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,321)", [user])
    Sql.query!("INSERT INTO economy_v2_account_progress(user_id,xp) VALUES ($1,1000)", [user])
    Sql.query!("INSERT INTO economy_v2_career_selections(user_id,career_code) VALUES ($1,'ballet')", [user])
    Sql.query!("INSERT INTO economy_v2_career_progress(user_id,career_code,xp) VALUES ($1,'ballet',400)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_pink_slip_dress',1),($1,'soft_rose_makeup',1)", [user])
    Sql.query!("INSERT INTO economy_v2_loadout(user_id,slot,item_id) VALUES ($1,'dress','soft_pink_slip_dress')", [user])
    Sql.query!("INSERT INTO economy_v2_cosmetic_selections(user_id,slot,item_id) VALUES ($1,'makeup','soft_rose_makeup')", [user])
    assert {:ok, response} = Queries.execute(request("profile.snapshot", user))
    profile = response["result"]
    assert profile["balance"] == "321"
    assert profile["progression"]["xp"] == "1000"
    assert profile["progression"]["level"] == 4
    assert profile["progression"]["active_career"]["code"] == "ballet"
    assert [%{"slot" => "dress", "name" => "Soft Pink Slip Dress"}] =
      Enum.map(profile["loadout"], &Map.take(&1, ["slot", "name"]))
    assert [%{"slot" => "makeup", "name" => "Soft Rose Makeup Style"}] =
      Enum.map(profile["cosmetics"], &Map.take(&1, ["slot", "name"]))
  end

  test "permanent cosmetic selection requires ownership and remains distinct from consumables" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_rose_makeup',1)", [user])
    selection = request("inventory.cosmetic.select", user,
      %{"item_id" => "soft_rose_makeup", "slot" => "makeup"})
    assert {:ok, first} = Equipment.execute(selection)
    assert {:ok, replay} = Equipment.execute(%{selection | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert [["soft_rose_makeup"]] =
      Sql.query!("SELECT item_id FROM economy_v2_cosmetic_selections WHERE user_id=$1 AND slot='makeup'", [user]).rows
    assert [[1]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='soft_rose_makeup'", [user]).rows
  end

  test "wardrobe inventory filters by category and paginates persisted ownership" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1),($1,'pink_lace_cami',1),($1,'soft_rose_makeup',1)", [user])
    assert {:ok, page} = Queries.execute(request("inventory.list", user, %{"category" => "fashion", "page" => 0}))
    assert page["result"]["total_items"] == 2
    assert Enum.all?(page["result"]["items"], &(&1["category"] == "fashion"))
    assert page["result"]["total_pages"] == 1
    assert {:ok, wardrobe} = Queries.execute(request("wardrobe.list", user, %{"category" => "all", "page" => 0}))
    assert wardrobe["result"]["total_items"] == 3
    assert Enum.all?(wardrobe["result"]["items"], &(&1["equip_slots"] != [] or &1["cosmetic_slots"] != []))
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
    response = Plug.Test.conn(:post, "/internal/economy/v1/execute", Jason.encode!(%{
      "request_id" => req.request_id, "idempotency_key" => req.idempotency_key,
      "operation" => req.operation, "context" => req.context, "args" => req.args
    }))
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
    item = Enum.find(rotation["items"], fn item ->
      is_nil(item["remaining"]) and item["level_requirement"] == 1 and
        is_nil(item["career_requirement"])
    end)
    assert item
    args = %{"item_id" => item["item_id"], "quantity" => 1,
             "period_key" => rotation["period_key"]}
    first_request = request("shop.purchase", user, args)
    assert {:ok, first} = Shop.execute(first_request)
    assert first["status"] == "ok"
    assert {:ok, replay} = Shop.execute(%{first_request | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1",
      [first_request.idempotency_key]).rows

    browse = request("shop.rotation", user, %{"category" => "all", "page" => 0})
    assert {:ok, page} = Shop.execute(browse)
    assert page["result"]["total_items"] >= 12
    assert Enum.any?(page["result"]["items"], &(&1["state"] in ["available", "owned", "locked", "sold"]))
    assert {:ok, details} = Shop.execute(request("shop.item", user, %{
      "item_id" => item["item_id"], "period_key" => rotation["period_key"]}))
    assert details["result"]["name"] == item["name"]
  end

  test "configured career XP is idempotent and does not invent a threshold" do
    user = snowflake()
    source = "test_" <> snowflake()
    Sql.query!("INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active) VALUES ($1,7,60000,'ballet',true)", [source])
    req = request("progression.grant", user, %{"source_code" => source})
    assert {:ok, first} = Progression.execute(req)
    assert first["result"]["xp"] == "7"
    assert [[7]] = Sql.query!("SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'", [user]).rows
    assert {:ok, replay} = Progression.execute(%{req | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert {:ok, cooldown} = Progression.execute(request("progression.grant", user, %{"source_code" => source}))
    assert cooldown["error"]["code"] == "COOLDOWN_ACTIVE"
  end

  test "marketplace listing escrows an owned item and replay preserves quantity" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1)", [user])
    req = request("marketplace.list", user, %{"item_id" => "leopard_baby_tee", "quantity" => 1,
      "ask_price" => "90", "expires_hours" => 24})
    assert {:ok, first} = Marketplace.execute(req)
    assert first["status"] == "ok"
    assert {:ok, replay} = Marketplace.execute(%{req | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert [] == Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='leopard_baby_tee'", [user]).rows
  end

  test "marketplace purchase transfers item and credits exactly once" do
    seller = snowflake()
    buyer = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,0),($2,500)", [seller, buyer])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'pink_lace_cami',1)", [seller])
    listing = request("marketplace.list", seller, %{"item_id" => "pink_lace_cami", "quantity" => 1,
      "ask_price" => "90", "expires_hours" => 24})
    assert {:ok, %{"result" => %{"listing_id" => id}}} = Marketplace.execute(listing)
    assert {:ok, browse} = Marketplace.execute(request("marketplace.browse", buyer,
      %{"category" => "fashion", "page" => 0}))
    assert browse["result"]["total_items"] == 1
    assert hd(browse["result"]["listings"])["listing_id"] == id
    assert {:ok, detail} = Marketplace.execute(request("marketplace.inspect", buyer, %{"listing_id" => id}))
    assert detail["result"]["seller_user_id"] == seller
    assert detail["result"]["ask_price"] == "90"
    purchase = request("marketplace.buy", buyer, %{"listing_id" => id})
    assert {:ok, first} = Marketplace.execute(purchase)
    assert first["status"] == "ok"
    assert {:ok, replay} = Marketplace.execute(%{purchase | request_id: Ecto.UUID.generate()})
    assert replay["result"] == first["result"]
    assert [[90]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [seller]).rows
    assert [[410]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [buyer]).rows
    assert [[1]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='pink_lace_cami'", [buyer]).rows
    assert [[2]] = Sql.query!("SELECT count(*) FROM economy_v2_ledger_entries WHERE request_key=$1",
      [purchase.idempotency_key]).rows
  end

  test "seller can reclaim an expired escrow and loadout requires ownership" do
    seller = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [seller])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'soft_pink_slip_dress',1)", [seller])
    equip = request("inventory.equip", seller, %{"item_id" => "soft_pink_slip_dress", "slot" => "dress"})
    assert {:ok, %{"status" => "ok"}} = Equipment.execute(equip)
    listing = request("marketplace.list", seller, %{"item_id" => "soft_pink_slip_dress", "quantity" => 1,
      "ask_price" => "100", "expires_hours" => 1})
    assert {:ok, %{"result" => %{"listing_id" => id}}} = Marketplace.execute(listing)
    assert [] = Sql.query!("SELECT slot FROM economy_v2_loadout WHERE user_id=$1", [seller]).rows
    Sql.query!("UPDATE economy_v2_marketplace_listings SET expires_at=now()-interval '1 second' WHERE listing_id=$1", [id])
    cancel = request("marketplace.cancel", seller, %{"listing_id" => id})
    assert {:ok, %{"status" => "ok"}} = Marketplace.execute(cancel)
    assert [[1]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='soft_pink_slip_dress'", [seller]).rows
  end

  test "wardrobe enforces dress slot conflicts and inventory inspection uses owned catalog data" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'leopard_baby_tee',1),($1,'soft_pink_slip_dress',1)", [user])
    assert {:ok, _} = Equipment.execute(request("inventory.equip", user, %{"item_id" => "leopard_baby_tee", "slot" => "top"}))
    assert {:ok, _} = Equipment.execute(request("inventory.equip", user, %{"item_id" => "soft_pink_slip_dress", "slot" => "dress"}))
    assert [["dress", "soft_pink_slip_dress"]] = Sql.query!("SELECT slot,item_id FROM economy_v2_loadout WHERE user_id=$1", [user]).rows
    assert {:ok, detail} = Queries.execute(request("inventory.item", user, %{"item_id" => "soft_pink_slip_dress"}))
    assert detail["result"]["description"] == "A simple glossy evening look."
    assert detail["result"]["equip_slots"] == ["dress"]
  end

  test "gather uses existing tool, wallet, inventory and cooldown atomically" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'fishing_rod',1)", [user])
    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')", [user])
    req = request("activity.perform", user, %{"activity" => "fish"})
    # Two controlled rolls: lowest credit reward and first fish-crate threshold.
    assert {:ok, first} = Activity.execute(req, fn _ -> 1 end)
    assert first["result"]["credits"] == "10"
    assert first["result"]["drop_item_id"] == "fish_crate"
    assert {:ok, replay} = Activity.execute(%{req | request_id: Ecto.UUID.generate()}, fn _ -> 100 end)
    assert replay["result"] == first["result"]
    assert {:ok, cooldown} = Activity.execute(request("activity.perform", user, %{"activity" => "fish"}))
    assert cooldown["error"]["code"] == "COOLDOWN_ACTIVE"
    assert [[10]] = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1", [user]).rows
    assert [[1]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='fish_crate'", [user]).rows
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_activity_events WHERE request_key=$1", [req.idempotency_key]).rows
  end

  test "configured activity XP commits with the gather result" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'axe',1)", [user])
    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'axe','axe')", [user])
    Sql.query!("""
      INSERT INTO economy_v2_xp_sources(source_code,reward_xp,cooldown_ms,career_code,active)
      VALUES ('activity_chop',7,60000,NULL,true)
      ON CONFLICT (source_code) DO UPDATE SET reward_xp=7,cooldown_ms=60000,career_code=NULL,active=true
    """)
    req = request("activity.perform", user, %{"activity" => "chop"})
    assert {:ok, result} = Activity.execute(req, fn _ -> 1 end)
    assert result["result"]["xp"]["xp_awarded"] == "7"
    assert [[7]] = Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_xp_events WHERE request_key=$1", [req.idempotency_key]).rows
  end

  test "configured consumable use is persistent and replay-safe" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'practice_water',2)", [user])
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
    assert [[1]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='practice_water'", [user]).rows
    assert [[1]] = Sql.query!("SELECT count(*) FROM economy_v2_active_effects WHERE user_id=$1 AND effect_code='test_refresh'", [user]).rows
    assert {:ok, inspected} = Queries.execute(request("inventory.item", user, %{"item_id" => "practice_water"}))
    assert inspected["result"]["effect_code"] == "test_refresh"
    assert inspected["result"]["effect_duration_ms"] == 60_000
    assert inspected["result"]["effect_active"]
    assert {:ok, effects} = Consumables.execute(request("inventory.effects", user))
    assert Enum.any?(effects["result"]["effects"], &(&1["effect_code"] == "test_refresh"))
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'fishing_rod',1)", [user])
    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')", [user])
    Sql.query!("""
      INSERT INTO economy_v2_activity_effect_rules(effect_code,activity,credit_bonus_percent,active)
      VALUES ('test_refresh','fish',50,true)
      ON CONFLICT (effect_code,activity) DO UPDATE SET credit_bonus_percent=50,active=true
    """)
    assert {:ok, gather} = Activity.execute(request("activity.perform", user, %{"activity" => "fish"}), fn _ -> 1 end)
    assert gather["result"]["credits"] == "15"
  end

  test "starter consumable effects apply once through the gated activity domain" do
    user = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1)", [user])
    Sql.query!("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES ($1,'practice_water',1),($1,'fishing_rod',1)", [user])
    Sql.query!("INSERT INTO economy_equipment(user_id,slot,item_id) VALUES ($1,'rod','fishing_rod')", [user])
    consume = request("inventory.consume", user, %{"item_id" => "practice_water"})
    assert {:ok, used} = Consumables.execute(consume)
    assert used["result"]["effect_code"] == "hydration_boost"
    assert {:ok, replay} = Consumables.execute(%{consume | request_id: Ecto.UUID.generate()})
    assert replay["result"] == used["result"]
    assert [[0]] = Sql.query!("SELECT quantity FROM economy_inventory WHERE user_id=$1 AND item_id='practice_water'", [user]).rows

    gather = request("activity.perform", user, %{"activity" => "fish"})
    assert {:ok, result} = Activity.execute(gather, fn _ -> 1 end)
    assert result["result"]["credits"] == "11"
  end

  defp restore(name, nil), do: System.delete_env(name)
  defp restore(name, value), do: System.put_env(name, value)
end
