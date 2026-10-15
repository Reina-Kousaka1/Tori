defmodule ToriEconomy.Social.ActionRegistryTest do
  use ExUnit.Case, async: true

  alias ToriEconomy.Social.ActionRegistry
  alias ToriEconomy.Social

  @actions ~w(hug kiss pat cuddle poke slap bite tickle highfive boop wave)

  test "all initial actions share the central target and 24-hour cooldown policy" do
    assert Enum.sort(Enum.map(ActionRegistry.all(), & &1.id)) == Enum.sort(@actions)
    assert Enum.sort(ActionRegistry.command_names()) == Enum.sort(@actions)

    for action <- ActionRegistry.all() do
      assert action.command_name == action.id
      assert action.target_required
      refute action.allow_self
      assert action.cooldown_policy == :actor_target_action
      assert action.cooldown_ms == 86_400_000
      assert action.social_effect == :none
      assert action.result_type == "social_action"
    end
  end

  test "target policy rejects missing/self targets and unknown actions" do
    assert :ok = ActionRegistry.validate_target("hug", "actor", "target")
    assert {:error, "INVALID_TARGET"} = ActionRegistry.validate_target("hug", "actor", nil)
    assert {:error, "INVALID_TARGET"} = ActionRegistry.validate_target("hug", "actor", "actor")
    assert {:error, "UNKNOWN_ACTION"} = ActionRegistry.validate_target("dance", "actor", "target")
  end

  test "cooldown boundary is exact and clock rollback fails closed" do
    duration = ActionRegistry.cooldown_ms()
    assert Social.cooldown_remaining_ms(10_000, 10_000 - duration + 1, duration) == 1
    assert Social.cooldown_remaining_ms(10_000, 10_000 - duration, duration) == 0
    assert Social.cooldown_remaining_ms(10_000, 10_000 - duration - 1, duration) == 0
    assert Social.cooldown_remaining_ms(9_999, 10_000, duration) == duration
  end
end
