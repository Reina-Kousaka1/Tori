defmodule ToriEconomy.Discord.AdapterTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Contract
  alias ToriEconomy.Dispatcher
  alias ToriEconomy.Discord.Adapter
  alias ToriEconomy.Discord.NostrumConsumer
  alias ToriEconomy.Discord.Commands

  test "does not take ownership of existing JDA commands" do
    assert :ignore == Adapter.handle(%{data: %{name: "daily"}})
    assert :ignore == Adapter.handle(%{data: %{name: "shop"}})
    assert :ignore == Adapter.handle(%{data: %{name: "music"}})
  end

  test "preview requires a guild and a real interaction identity" do
    assert :ignore ==
             Adapter.handle(%{
               data: %{name: "tori-profile-preview"},
               id: 1,
               guild_id: nil,
               channel_id: 2
             })
  end

  test "only mutations receive a stable Discord interaction idempotency key" do
    interaction_id = 123_456_789_012_345_678

    assert Adapter.idempotency_key("shop.purchase", interaction_id) ==
             "discord-interaction:123456789012345678"

    assert Adapter.idempotency_key("profile.snapshot", interaction_id) == nil
    assert Adapter.idempotency_key("unknown.operation", interaction_id) == nil

    context = %{
      "actor_user_id" => Integer.to_string(interaction_id),
      "guild_id" => "234567890123456789",
      "channel_id" => "345678901234567890"
    }

    assert {:ok, %{operation: "shop.purchase"}} =
             Contract.validate(%{
               "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
               "idempotency_key" => Adapter.idempotency_key("shop.purchase", interaction_id),
               "operation" => "shop.purchase",
               "context" => context,
               "args" => %{
                 "item_id" => "leopard_baby_tee",
                 "quantity" => 1,
                 "period_key" => "20260930"
               }
             })

    assert {:ok, %{operation: "profile.snapshot"}} =
             Contract.validate(%{
               "request_id" => "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
               "idempotency_key" => Adapter.idempotency_key("profile.snapshot", interaction_id),
               "operation" => "profile.snapshot",
               "context" => context,
               "args" => %{}
             })
  end

  test "legacy adapter helpers remain separate from main-bot command ownership" do
    commands = Adapter.preview_commands()

    assert Enum.sort(commands) ==
             Enum.sort([
               "profile",
               "career",
               "tori-profile-preview",
               "tori-shop-preview",
               "tori-wardrobe-preview",
               "tori-marketplace-preview",
               "tori-career-preview",
               "tori-persona-preview",
               "tori-consumable-preview",
               "tori-activity-preview"
             ])

    assert :ignore == Adapter.handle(%{data: %{name: "marketplace"}})
  end

  test "the main-bot command definitions contain only career and profile" do
    assert Enum.sort(Enum.map(Commands.definitions(), & &1["name"])) == ["career", "profile"]
    profile = Enum.find(Commands.definitions(), &(&1["name"] == "profile"))
    assert [%{"name" => "user", "type" => 6, "required" => false}] = profile["options"]

    career = Enum.find(Commands.definitions(), &(&1["name"] == "career"))
    assert [%{"name" => "status"}, %{"name" => "select"}, activity, %{"name" => "practice"}] =
             career["options"]

    action = Enum.find(activity["options"], &(&1["name"] == "action_code"))
    values = Enum.map(action["choices"], & &1["value"])
    assert Enum.uniq(values) == values

    assert Enum.sort(values) ==
             Enum.sort(~w(
               class barre rehearsal performance court_practice drills scrimmage match
               squad_practice tumbling_stunts routine competition
             ))

    assert Dispatcher.mood_event_for_result(%{
             "type" => "career_practice",
             "level_up" => true,
             "rare_drop" => true
           }) == nil
  end

  test "career activity responses show bounded XP and Credits rewards" do
    response = %{
      "status" => "ok",
      "result" => %{
        "type" => "career_practice",
        "career_name" => "Ballet",
        "action_code" => "barre",
        "xp_awarded" => "20",
        "credits_awarded" => "14",
        "career_level" => 2
      },
      "presentation" => %{"text" => "Nice work at the barre."}
    }

    assert {:ok, content} = Adapter.render_result(:career, response, "123")
    assert content =~ "Nice work at the barre."
    assert content =~ "Ballet barre · +20 XP · +14 Credits · career level 2"
  end

  test "career overview uses selected career action availability" do
    response = %{
      "status" => "ok",
      "result" => %{
        "type" => "career_snapshot",
        "careers" => [
          %{"code" => "ballet", "name" => "Ballet", "level" => 2, "xp" => "120"}
        ],
        "active_career" => %{
          "code" => "ballet",
          "name" => "Ballet",
          "level" => 2,
          "xp" => "120",
          "actions" => [
            %{
              "action_code" => "rehearsal",
              "display_name" => "Rehearsal",
              "reward_xp" => "24",
              "reward_credits" => "18",
              "required_career_level" => 3,
              "required_item_id" => "rehearsal_wrap_skirt",
              "required_item_name" => "Rehearsal Wrap Skirt",
              "cooldown_remaining_ms" => 0,
              "availability" => "career_level"
            }
          ]
        }
      }
    }

    assert {:ok, content} = Adapter.render_result(:career, response, "123")
    assert content =~ "Activities:"
    assert content =~ "Rehearsal"
    assert content =~ "requires career level 3"
  end

  test "shop navigation reads the category/page and component IDs stay scoped to shop pages" do
    command = %{
      data: %{
        name: "tori-shop-preview",
        options: [
          %{
            name: "browse",
            options: [%{name: "category", value: "fashion"}, %{name: "page", value: 2}]
          }
        ]
      }
    }

    assert {:command, "fashion", 2} = Adapter.shop_navigation(command)

    assert {:component, "fashion", 3} =
             Adapter.shop_navigation(%{data: %{custom_id: "tori-shop-page:fashion:3"}})

    assert {:component, "beauty", 0} =
             Adapter.shop_navigation(%{
               data: %{custom_id: "tori-shop-category", values: ["beauty"]}
             })

    assert :ignore = Adapter.shop_navigation(%{data: %{custom_id: "other:command:1"}})
    assert :ignore = Adapter.shop_navigation(%{data: %{custom_id: "tori-shop-page:fashion:9999"}})

    assert :ignore =
             Adapter.shop_navigation(%{
               data: %{custom_id: "tori-shop-category", values: ["arbitrary"]}
             })
  end

  test "Nostrum acknowledges only Elixir-owned main-bot commands" do
    career = %{data: %{name: "career"}}
    profile = %{data: %{name: "profile"}}
    component = %{data: %{custom_id: "tori-shop-category"}}

    assert Enum.sort(Adapter.nostrum_commands()) == ["career", "profile"]
    assert Adapter.supported_interaction?(career)
    assert Adapter.supported_interaction?(profile)
    refute Adapter.supported_interaction?(%{data: %{name: "daily"}})
    refute Adapter.supported_interaction?(%{data: %{name: "status"}})
    refute Adapter.supported_interaction?(%{data: %{name: "tori-shop-preview"}})
    refute Adapter.supported_interaction?(component)
    assert NostrumConsumer.acknowledgement(career) == %{type: 5, data: %{flags: 64}}
    assert NostrumConsumer.acknowledgement(profile) == %{type: 5, data: %{flags: 64}}
  end

  test "consumer uses Nostrum's supervised start_link/1 child contract" do
    assert {NostrumConsumer, :start_link, [[]]} = NostrumConsumer.child_spec([]).start
  end

  test "consumable inspect and use results keep their distinct presentations" do
    item = %{
      "type" => "inventory_item",
      "name" => "Training Tea",
      "quantity" => 2,
      "effect_active" => true,
      "effect_code" => "focus",
      "effect_duration_ms" => 120_000
    }

    assert {:ok, "Training Tea · quantity 2 · configured: focus · 2 min"} =
             Adapter.render_result(:consumables, %{"status" => "ok", "result" => item}, nil)

    used = %{
      "type" => "consumable_used",
      "item_name" => "Training Tea",
      "effect_code" => "focus",
      "expires_at_ms" => "1780000000000"
    }

    assert {:ok, "Used Training Tea · effect focus · expires <t:1780000000:R>."} =
             Adapter.render_result(:consumables, %{"status" => "ok", "result" => used}, nil)

    assert {:ok, "No active consumable effects."} =
             Adapter.render_result(
               :consumables,
               %{"status" => "ok", "result" => %{"type" => "active_effects", "effects" => []}},
               nil
             )
  end

  test "shop preview exposes every category and keeps page controls on the active drop" do
    content = "Y2K drop · winter · page 2/4"
    payload = NostrumConsumer.response_data(content, {:command, "fashion", 1})
    [category_row, page_row] = payload.components
    [category_menu] = category_row.components

    assert category_menu.custom_id == "tori-shop-category"
    assert Enum.any?(category_menu.options, &(&1.value == "all" and &1.label == "All styles"))
    assert Enum.any?(category_menu.options, &(&1.value == "fashion" and &1.default))
    assert Enum.any?(page_row.components, &(&1.custom_id == "tori-shop-page:fashion:0"))
    assert Enum.any?(page_row.components, &(&1.custom_id == "tori-shop-page:fashion:2"))
    assert payload.allowed_mentions == %{parse: []}
  end
end
