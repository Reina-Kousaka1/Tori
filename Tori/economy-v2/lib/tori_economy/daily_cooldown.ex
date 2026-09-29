defmodule ToriEconomy.DailyCooldown do
  @moduledoc "Shared 24-hour, epoch-millisecond cooldown semantics for daily claims."

  @duration_ms 24 * 60 * 60 * 1_000

  def duration_ms, do: @duration_ms

  def remaining_ms(now, last_claim) when is_integer(now) and is_integer(last_claim) do
    cond do
      last_claim <= 0 -> 0
      now < last_claim -> @duration_ms
      true -> max(0, @duration_ms - (now - last_claim))
    end
  end
end
