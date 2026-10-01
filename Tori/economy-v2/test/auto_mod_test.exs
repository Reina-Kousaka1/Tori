defmodule ToriEconomy.AutoModTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.AutoMod.{Guild, Policy}

  test "each guild detects a join burst once per window" do
    {:ok, first} = Guild.start_link(policy: %{join_limit: 3, join_window_ms: 1_000})
    {:ok, second} = Guild.start_link(policy: %{join_limit: 3, join_window_ms: 1_000})
    on_exit(fn ->
      stop_if_alive(first)
      stop_if_alive(second)
    end)

    assert Guild.observe_join(first, 1, 100) == nil
    assert Guild.observe_join(first, 2, 200) == nil
    assert %{"rule" => "join_burst", "observed_count" => 3, "escalation" => "review"} =
             Guild.observe_join(first, 3, 300)

    assert Guild.observe_join(first, 4, 400) == nil
    assert Guild.observe_join(second, 5, 400) == nil
    assert Guild.observe_join(first, 6, 2_000) == nil
    assert Guild.observe_join(first, 7, 2_100) == nil
    assert %{"rule" => "join_burst"} = Guild.observe_join(first, 8, 2_200)
  end

  test "flood, mentions, invites, deduplication and overrides use bounded guild state" do
    {:ok, server} =
      Guild.start_link(
        policy: %{flood_limit: 3, mention_limit: 2, flood_window_ms: 1_000}
      )

    on_exit(fn -> stop_if_alive(server) end)

    assert Guild.observe_message(server, 1, 10, 100, "hello", 0, 100) == nil
    assert Guild.observe_message(server, 1, 10, 100, "hello", 0, 110) == nil
    assert Guild.observe_message(server, 1, 10, 101, "hello", 0, 200) == nil

    assert %{"rule" => "flood", "observed_count" => 3} =
             Guild.observe_message(server, 1, 10, 102, "hello", 0, 300)

    assert Guild.observe_message(server, 1, 10, 103, "hello", 0, 400) == nil

    assert %{"rule" => "mentions"} =
             Guild.observe_message(server, 2, 10, 104, "hi", 2, 400)

    assert %{"rule" => "invite"} =
             Guild.observe_message(server, 2, 10, 105, "discord.gg/example", 0, 500)

    assert :ok = Guild.override(server, 2, true)
    assert Guild.observe_message(server, 2, 10, 106, "discord.gg/example", 4, 600) == nil
    assert :ok = Guild.override(server, 2, false)

    assert :ok = Guild.configure(server, %{allow_channels: MapSet.new([10])})
    assert Guild.observe_message(server, 2, 10, 107, "discord.gg/example", 4, 700) == nil
    assert {:error, :invalid_policy} = Guild.configure(server, %{mention_limit: 0})
  end

  defp stop_if_alive(pid) do
    if Process.alive?(pid), do: GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end

  test "thresholds and escalation rules reject invalid configuration" do
    assert {:error, :invalid_policy} = Policy.validate(%{join_limit: 1})
    assert {:error, :invalid_policy} = Policy.validate(%{unexpected: true})
    assert {:ok, config} = Policy.validate(%{join_limit: 10})
    assert config.join_limit == 10
    assert Policy.invite?("https://discord.com/invite/sample")
    refute Policy.invite?("a normal school message")
  end
end
