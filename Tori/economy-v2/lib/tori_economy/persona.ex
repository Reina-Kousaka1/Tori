defmodule ToriEconomy.Persona do
  @moduledoc "Presentation-only persona snapshot and semantic phrases. No domain decision uses this module."
  alias ToriEconomy.Persona.{Mood, Season}

  @contexts ~w(general economy shop inventory profile ballet volleyball cheer activities social moderation administration system)a
  @phrases %{
    "daily.success" => %{
      neutral: "You received %{amount} Credits.",
      happy: "A little win for today: %{amount} Credits 🎀",
      christmas: "A little winter sparkle: %{amount} Credits ✨"
    },
    "daily.cooldown" => %{neutral: "Your next claim is available in %{cooldown}."},
    "shop.purchase.success" => %{
      neutral: "Purchased %{item_name} for %{amount} Credits.",
      excited: "That %{item_name} is yours! %{amount} Credits spent ✨"
    },
    "shop.purchase.insufficient_funds" => %{neutral: "You need more Credits for %{item_name}."},
    "inventory.empty" => %{neutral: "Your inventory is empty."},
    "activity.success" => %{neutral: "You received %{item_name}."},
    "profile.header" => %{neutral: "%{username}'s profile"},
    "system.error" => %{neutral: "Something went wrong. Please try again."}
  }

  def snapshot(opts \\ []) do
    context = Keyword.get(opts, :context, :general)
    context = if context in @contexts, do: context, else: :general
    date = Keyword.get(opts, :date, Date.utc_today())
    mood = Keyword.get_lazy(opts, :mood, fn -> Mood.snapshot() end)

    %{identity: :tori, context: context, mood: mood.mood,
      intensity: mood.intensity, season: Season.current(date, Keyword.get(opts, :event))}
  end

  def render(key, variables, %{context: context, mood: mood, intensity: intensity, season: season})
      when is_binary(key) and is_map(variables) do
    variants = Map.get(@phrases, key, %{neutral: key})

    template =
      if context in [:moderation, :administration, :system] or intensity < 0.3 do
        variants.neutral
      else
        Map.get(variants, season, Map.get(variants, mood, variants.neutral))
      end

    Regex.replace(~r/%\{([a-z_]+)\}/, template, fn full, name ->
      case Map.fetch(variables, name) do
        {:ok, value} when is_binary(value) -> value
        {:ok, value} when is_integer(value) -> Integer.to_string(value)
        _ -> full
      end
    end)
  end
end
