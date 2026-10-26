defmodule ToriEconomy.Discord.ConfigTest do
  use ExUnit.Case, async: true

  alias ToriEconomy.Discord.Config

  @guild_id "234567890123456789"

  test "disabled Nostrum requires no Discord configuration" do
    assert :disabled = Config.load(%{"TORI_NOSTRUM_ENABLED" => "false"})
    assert :disabled = Config.load(%{})
  end

  test "enabled Nostrum uses the existing Discord token and guild ID" do
    env = %{
      "TORI_NOSTRUM_ENABLED" => "true",
      "DISCORD_TOKEN" => "test-main-bot-token",
      "DISCORD_GUILD_ID" => @guild_id
    }

    assert {:enabled, %{token: "test-main-bot-token", guild_id: @guild_id}} = Config.load(env)
  end

  test "enabled Nostrum reports missing or malformed Discord configuration safely" do
    assert {:error, "DISCORD_TOKEN is required when TORI_NOSTRUM_ENABLED=true"} =
             Config.load(%{"TORI_NOSTRUM_ENABLED" => "true", "DISCORD_GUILD_ID" => @guild_id})

    assert {:error, "DISCORD_GUILD_ID is required when TORI_NOSTRUM_ENABLED=true"} =
             Config.load(%{
               "TORI_NOSTRUM_ENABLED" => "true",
               "DISCORD_TOKEN" => "private-test-value"
             })

    assert {:error, "DISCORD_GUILD_ID must be a 17–20 digit Discord server ID"} =
             Config.load(%{
               "TORI_NOSTRUM_ENABLED" => "true",
               "DISCORD_TOKEN" => "private-test-value",
               "DISCORD_GUILD_ID" => "invalid"
             })

    assert_raise ArgumentError, "DISCORD_TOKEN is required when TORI_NOSTRUM_ENABLED=true", fn ->
      Config.load!(%{"TORI_NOSTRUM_ENABLED" => "true"})
    end
  end
end
