defmodule ToriEconomy.Persona.Presence do
  @moduledoc "Pure status suggestions. The existing Java scheduler remains the only live presence owner."

  @base ["at ballet practice 🩰", "ready for volleyball 🏐", "taking a little break ✨"]
  @competitive ["one more set 🏐", "practice first, victory next ✨"]
  @sleepy ["resting after practice 🌙", "dreaming of the next performance 🩰"]
  @seasonal %{
    christmas: ["winter practice and warm wishes ❄️"],
    halloween: ["spooky little practice night 🎃"],
    valentines: ["sending a little love 💗"],
    summer: ["summer courts and sunshine ☀️"]
  }

  def choose(%{mood: mood, season: season}, seed, previous \\ nil) when is_integer(seed) do
    pool = @base ++ Map.get(@seasonal, season, []) ++ mood_entries(mood)
    candidates = if length(pool) > 1, do: Enum.reject(pool, &(&1 == previous)), else: pool
    Enum.at(candidates, rem(abs(seed), length(candidates)))
  end

  defp mood_entries(mood) when mood in [:competitive, :focused, :excited], do: @competitive
  defp mood_entries(:sleepy), do: @sleepy
  defp mood_entries(_), do: []
end
