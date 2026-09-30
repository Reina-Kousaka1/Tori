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
    "shop.rotation.ready" => %{
      neutral: "The %{theme} drop is live for %{minutes} more minutes.",
      excited: "A fresh %{theme} drop is here ✨ It refreshes in %{minutes} minutes.",
      christmas: "A little winter shop update is here ✨ %{minutes} minutes remain."
    },
    "shop.item.rare" => %{
      neutral: "%{item_name} is a %{rarity} item and costs %{amount} Credits.",
      excited: "A rare find: %{item_name} ✨ It costs %{amount} Credits."
    },
    "wardrobe.equip.success" => %{
      neutral: "Equipped %{item_name} in your %{slot} slot.",
      playful: "%{item_name} is in your %{slot} look now 🎀"
    },
    "wardrobe.cosmetic.success" => %{
      neutral: "Selected %{item_name} for your %{slot} style.",
      happy: "%{item_name} is your new %{slot} style ✨"
    },
    "career.select.success" => %{
      neutral: "Selected %{career}. Your existing progress is saved.",
      excited: "%{career} is your focus now ✨ Your earlier progress stays saved."
    },
    "career.practice.success" => %{
      neutral: "%{career} practice complete: +%{amount} XP.",
      focused: "Practice logged. %{career} +%{amount} XP.",
      excited: "That practice counted ✨ %{career} +%{amount} XP."
    },
    "career.practice.cooldown" => %{neutral: "You can practice again in %{cooldown}."},
    "progression.level_up" => %{
      neutral: "You reached level %{level}.",
      excited: "Level %{level} unlocked ✨"
    },
    "marketplace.list.success" => %{
      neutral: "Listed %{item_name} ×%{quantity} for %{amount} Credits."
    },
    "marketplace.cancel.success" => %{
      neutral: "The listing was cancelled and the item returned to your inventory."
    },
    "consumable.use.success" => %{
      neutral: "Used %{item_name}. The configured effect is active until %{expires_at}."
    },
    "activity.fish.success" => %{neutral: "You caught %{item_name} and earned %{amount} Credits."},
    "activity.mine.success" => %{neutral: "You found %{item_name} and earned %{amount} Credits."},
    "activity.chop.success" => %{
      neutral: "You gathered %{item_name} and earned %{amount} Credits."
    },
    "profile.progress" => %{neutral: "Level %{level} · %{xp} XP"},
    "marketplace.purchase.success" => %{
      neutral: "Purchased %{item_name} from the marketplace for %{amount} Credits.",
      happy: "%{item_name} found a new home! %{amount} Credits spent ✨"
    },
    "progression.grant.success" => %{
      neutral: "You gained %{amount} XP.",
      excited: "A little progress! You gained %{amount} XP ✨"
    },
    "inventory.empty" => %{neutral: "Your inventory is empty."},
    "activity.success" => %{neutral: "You received %{item_name}."},
    "profile.header" => %{neutral: "%{username}'s profile"},
    "system.error" => %{neutral: "Something went wrong. Please try again."}
  }

  def context_from_name(name) when is_binary(name) do
    Enum.find_value(@contexts, :error, fn context ->
      if Atom.to_string(context) == name, do: {:ok, context}, else: false
    end)
  end

  def context_from_name(_), do: :error

  def snapshot(opts \\ []) do
    context = Keyword.get(opts, :context, :general)
    context = if context in @contexts, do: context, else: :general
    date = Keyword.get(opts, :date, Date.utc_today())
    mood = Keyword.get_lazy(opts, :mood, &current_mood/0)

    %{
      identity: :tori,
      context: context,
      mood: mood.mood,
      intensity: mood.intensity,
      season: Season.current(date, Keyword.get(opts, :event))
    }
  end

  defp current_mood do
    Mood.snapshot()
  catch
    :exit, _ -> Mood.new(System.system_time(:millisecond))
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
