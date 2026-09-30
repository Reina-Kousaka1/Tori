defmodule ToriEconomy.Activity.Policy do
  @moduledoc "Pure parity with the legacy Java gathering thresholds and reward range."

  def tier(durability) when is_integer(durability) and durability > 0,
    do: durability |> div(40) |> max(1) |> min(8)

  def drop("fish", tier, roll) when tier in 1..8 and roll in 0..99 do
    cond do
      roll < 2 and tier >= 5 -> "shark"
      roll < 4 -> "fish_crate"
      roll < 8 and tier >= 3 -> "shell"
      roll < 16 -> "squid"
      roll < 25 -> "crab"
      roll < 40 -> "tropical_fish"
      roll < 50 -> "blowfish"
      true -> "fish"
    end
  end

  def drop("mine", tier, roll) when tier in 1..8 and roll in 0..99 do
    cond do
      roll < 2 and tier >= 8 -> "sparkle_fragment"
      roll < 7 and tier >= 4 -> "moon_rune"
      roll < 9 -> "mine_crate"
      roll < 24 -> "gem_fragment"
      roll < 40 -> "cobweb"
      true -> "rock"
    end
  end

  def drop("chop", tier, roll) when tier in 1..8 and roll in 0..99 do
    cond do
      roll < 2 and tier >= 8 -> "sparkle_fragment"
      roll < 7 and tier >= 4 -> "moon_rune"
      roll < 9 -> "chop_crate"
      roll < 20 -> "pear"
      roll < 35 -> "apple"
      roll < 50 -> "leaves"
      true -> "wood"
    end
  end

  def credits(tier, roll)
      when tier in 1..8 and is_integer(roll) and roll >= 0 and roll < 16 * tier,
      do: 10 * tier + roll
end
