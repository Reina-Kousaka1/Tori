defmodule ToriEconomy.Progression.PolicyTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Progression.Policy

  @thresholds [
    %{level: 1, required_xp: 0},
    %{level: 2, required_xp: 100},
    %{level: 3, required_xp: 300}
  ]

  test "uses supplied thresholds exactly, including boundaries and final level" do
    assert {:ok, %{level: 1, next_level_xp: 100}} = Policy.level_for(99, @thresholds)
    assert {:ok, %{level: 2, level_start_xp: 100}} = Policy.level_for(100, @thresholds)
    assert {:ok, %{level: 3, next_level_xp: nil}} = Policy.level_for(300, @thresholds)
  end

  test "rejects invalid xp and ambiguous or incomplete curves" do
    assert {:error, :invalid_xp} = Policy.level_for(-1, @thresholds)
    assert {:error, :invalid_thresholds} = Policy.level_for(0, [])
    assert {:error, :invalid_thresholds} = Policy.level_for(0, [%{level: 1, required_xp: 1}])
    assert {:error, :invalid_thresholds} = Policy.level_for(0, [
             %{level: 1, required_xp: 0}, %{level: 3, required_xp: 100}
           ])
  end
end
