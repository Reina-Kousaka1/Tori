defmodule ToriEconomy.Discord.AdapterTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Discord.Adapter

  test "does not take ownership of existing JDA commands" do
    assert :ignore == Adapter.handle(%{data: %{name: "daily"}})
    assert :ignore == Adapter.handle(%{data: %{name: "shop"}})
    assert :ignore == Adapter.handle(%{data: %{name: "music"}})
  end

  test "preview requires a guild and a real interaction identity" do
    assert :ignore == Adapter.handle(%{data: %{name: "tori-profile-preview"}, id: 1,
                                      guild_id: nil, channel_id: 2})
  end
end
