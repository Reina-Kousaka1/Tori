defmodule ToriEconomy.PersonaTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Persona
  alias ToriEconomy.Persona.{Mood, Season}

  test "mood changes in response to known events and decays without a timer or Discord" do
    initial = Mood.new(1_000)
    excited = Mood.transition(initial, :rare_drop, 2_000)
    assert excited.mood == :excited
    assert excited.reason == :rare_drop
    assert Mood.resolve(excited, 2_000).intensity == 0.75
    assert Mood.resolve(excited, 1_802_000).intensity < 0.75
    assert Mood.resolve(excited, 3_602_000).mood == :normal
    assert Mood.transition(initial, :unknown, 2_000) == initial
  end

  test "a supervised mood process shares one state but snapshots do not mutate it" do
    {:ok, mood} = Mood.start_link(name: nil, now_ms: 1_000)
    assert Mood.record(:volleyball_match, mood, 2_000).mood == :competitive
    assert Mood.snapshot(mood, 2_000).intensity == 0.65
    assert Mood.snapshot(mood, 3_602_000).mood == :normal
    assert Mood.snapshot(mood, 2_000).mood == :competitive
    GenServer.stop(mood)
  end

  test "season is deterministic and event overlays do not replace identity" do
    assert Season.current(~D[2026-02-14]) == :valentines
    assert Season.current(~D[2026-04-01]) == :spring
    assert Season.current(~D[2026-12-25]) == :christmas
    assert Season.current(~D[2026-07-01], :halloween) == :halloween
    assert Season.current(~D[2026-07-01], :unrecognized) == :summer
  end

  test "only configured event names resolve without creating atoms" do
    assert Mood.event_from_name("rare_drop") == {:ok, :rare_drop}
    assert Mood.event_from_name("made_up_event") == :error
  end

  test "repeating a mood event inside its cooldown does not refresh its duration" do
    initial = Mood.new(1_000)
    first = Mood.transition(initial, :successful_activity, 2_000)
    repeated = Mood.transition(first, :successful_activity, 60_000)
    assert repeated.started_at_ms == 2_000
    assert repeated.reason == :successful_activity
    refute repeated.intensity > first.intensity
  end

  test "persona phrases receive structured values and serious contexts stay factual" do
    mood = %{mood: :happy, intensity: 0.8}
    playful = Persona.snapshot(context: :economy, mood: mood, date: ~D[2026-04-01])
    serious = Persona.snapshot(context: :moderation, mood: mood, date: ~D[2026-04-01])

    assert Persona.render("daily.success", %{"amount" => 150}, playful) =~ "🎀"

    assert Persona.render("daily.success", %{"amount" => 150}, serious) ==
             "You received 150 Credits."

    assert Persona.render("unknown.key", %{}, playful) == "unknown.key"

    assert Persona.render("daily.cooldown", %{}, playful) ==
             "Your next claim is available in %{cooldown}."
  end
end
