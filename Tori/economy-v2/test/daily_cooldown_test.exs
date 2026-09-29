defmodule ToriEconomy.DailyCooldownTest do
  use ExUnit.Case, async: true

  alias ToriEconomy.DailyCooldown

  @day 24 * 60 * 60 * 1_000

  defp instant_ms(value) do
    {:ok, instant, _offset} = DateTime.from_iso8601(value)
    DateTime.to_unix(instant, :millisecond)
  end

  test "blocks until exactly 24 elapsed hours after the claim" do
    claim = instant_ms("2026-03-28T11:21:00Z")

    assert DailyCooldown.remaining_ms(claim + @day - 1, claim) == 1
    assert DailyCooldown.remaining_ms(claim + @day, claim) == 0
    assert DailyCooldown.remaining_ms(claim + @day + 1, claim) == 0
  end

  test "crossing midnight does not reset the cooldown" do
    claim = instant_ms("2026-09-28T11:21:00Z")
    after_midnight = instant_ms("2026-09-29T00:01:00Z")

    assert DailyCooldown.remaining_ms(after_midnight, claim) == 11 * 60 * 60_000 + 20 * 60_000
  end

  test "uses absolute elapsed time across a daylight-saving transition" do
    claim = instant_ms("2026-03-28T11:21:00Z")
    next_day = instant_ms("2026-03-29T11:21:00Z")

    assert DailyCooldown.remaining_ms(next_day, claim) == 0
  end

  test "the legacy zero timestamp remains immediately claimable" do
    assert DailyCooldown.remaining_ms(1_000, 0) == 0
  end
end
