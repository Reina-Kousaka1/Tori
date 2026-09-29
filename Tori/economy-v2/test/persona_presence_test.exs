defmodule ToriEconomy.Persona.PresenceTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Persona.Presence

  test "same snapshot and seed give a stable suggestion" do
    state = %{mood: :competitive, season: :summer}
    assert Presence.choose(state, 123) == Presence.choose(state, 123)
  end

  test "a previous status is avoided when choices exist" do
    state = %{mood: :sleepy, season: :winter}
    previous = Presence.choose(state, 3)
    refute Presence.choose(state, 3, previous) == previous
  end
end
