defmodule ToriEconomy.Discord.EconomyCommandsTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Sql, TestSchema}

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

  defp snowflake,
    do: Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))

  test "career slash select and activity use the shared Elixir engine" do
    System.put_env("TORI_ECONOMY_WRITE_ENABLED", "true")
    System.put_env("TORI_ECONOMY_WRITE_MODE", "test")
    System.put_env("TORI_ECONOMY_DATABASE_URL", @url)

    user = snowflake()
    user_id = String.to_integer(user)
    guild_id = String.to_integer("234567890123456789")
    channel_id = String.to_integer("345678901234567890")

    select = %{
      data: %{
        name: "career",
        options: [%{name: "select", options: [%{name: "career_code", value: "ballet"}]}]
      },
      id: String.to_integer(snowflake()),
      guild_id: guild_id,
      channel_id: channel_id,
      user: %{id: user_id}
    }

    assert {:ok, _selected} = ToriEconomy.Discord.Adapter.handle(select)

    activity = %{
      data: %{
        name: "career",
        options: [
          %{
            name: "activity",
            options: [
              %{name: "career_code", value: "ballet"},
              %{name: "action_code", value: "class"}
            ]
          }
        ]
      },
      id: String.to_integer(snowflake()),
      guild_id: guild_id,
      channel_id: channel_id,
      user: %{id: user_id}
    }

    assert {:ok, content} = ToriEconomy.Discord.Adapter.handle(activity)
    assert content =~ "Ballet class · +16 XP · +10 Credits · career level 1"

    assert [[16]] =
             Sql.query!(
               "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code='ballet'",
               [user]
             ).rows
  end

  test "profile command can read another user's profile without changing the actor" do
    actor = snowflake()
    target = snowflake()
    Sql.query!("INSERT INTO economy_accounts(user_id,balance) VALUES ($1,999),($2,123)", [actor, target])

    interaction = %{
      data: %{name: "profile", options: [%{name: "user", value: String.to_integer(target)}]},
      id: String.to_integer(snowflake()),
      guild_id: 234_567_890_123_456_789,
      channel_id: 345_678_901_234_567_890,
      user: %{id: String.to_integer(actor)}
    }

    assert {:ok, content} = ToriEconomy.Discord.Adapter.handle(interaction)
    assert content =~ "Tori profile • <@#{target}>"
    assert content =~ "Credits: 123"
    refute content =~ "Credits: 999"
  end

  defp restore(name, nil), do: System.delete_env(name)
  defp restore(name, value), do: System.put_env(name, value)
end
