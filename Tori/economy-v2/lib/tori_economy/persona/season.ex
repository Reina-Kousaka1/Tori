defmodule ToriEconomy.Persona.Season do
  @moduledoc "Pure calendar and event overlay selection; callers may inject a date."

  @events ~w(halloween christmas valentines)a

  def current(date \\ Date.utc_today(), event \\ nil)

  def current(%Date{} = date, nil) do
    case {date.month, date.day} do
      {10, day} when day >= 24 -> :halloween
      {12, day} when day >= 1 and day <= 26 -> :christmas
      {2, day} when day >= 7 and day <= 14 -> :valentines
      {month, _} when month in [3, 4, 5] -> :spring
      {month, _} when month in [6, 7, 8] -> :summer
      {month, _} when month in [9, 10, 11] -> :autumn
      _ -> :winter
    end
  end

  def current(%Date{}, event) when event in @events, do: event
end
