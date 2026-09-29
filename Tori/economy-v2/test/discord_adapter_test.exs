defmodule ToriEconomy.Discord.AdapterTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Discord.Adapter
  alias ToriEconomy.Discord.PreviewCommands

  test "does not take ownership of existing JDA commands" do
    assert :ignore == Adapter.handle(%{data: %{name: "daily"}})
    assert :ignore == Adapter.handle(%{data: %{name: "shop"}})
    assert :ignore == Adapter.handle(%{data: %{name: "music"}})
  end

  test "preview requires a guild and a real interaction identity" do
    assert :ignore == Adapter.handle(%{data: %{name: "tori-profile-preview"}, id: 1,
                                      guild_id: nil, channel_id: 2})
  end

  test "read-only preview routes cover the new domain surfaces without claiming JDA commands" do
    commands = Adapter.preview_commands()
    assert Enum.sort(commands) == Enum.sort([
      "tori-profile-preview", "tori-shop-preview", "tori-wardrobe-preview",
      "tori-marketplace-preview", "tori-career-preview", "tori-persona-preview",
      "tori-consumable-preview", "tori-activity-preview"
    ])
    assert :ignore == Adapter.handle(%{data: %{name: "marketplace"}})
  end

  test "shop navigation reads the category/page and component IDs stay scoped to shop pages" do
    command = %{data: %{name: "tori-shop-preview", options: [
      %{name: "browse", options: [%{name: "category", value: "fashion"}, %{name: "page", value: 2}]}
    ]}}
    assert {:command, "fashion", 2} = Adapter.shop_navigation(command)
    assert {:component, "fashion", 3} = Adapter.shop_navigation(%{data: %{custom_id: "tori-shop-page:fashion:3"}})
    assert :ignore = Adapter.shop_navigation(%{data: %{custom_id: "other:command:1"}})
    assert :ignore = Adapter.shop_navigation(%{data: %{custom_id: "tori-shop-page:fashion:9999"}})
  end

  test "Nostrum preview startup requires test mode and the exact connected test database" do
    assert PreviewCommands.safe_test_database?("test", "tori_economy_test", "tori_economy_test")
    refute PreviewCommands.safe_test_database?("production", "tori_economy_test", "tori_economy_test")
    refute PreviewCommands.safe_test_database?("test", "tori_main", "tori_main")
    refute PreviewCommands.safe_test_database?("test", "tori_economy_test", "tori_other_test")
    assert PreviewCommands.configured_database(url: "postgresql://preview@127.0.0.1:5433/tori_preview_test") ==
      "tori_preview_test"
    assert PreviewCommands.configured_database(database: "tori_named_test") == "tori_named_test"
  end
end
