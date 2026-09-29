defmodule ToriEconomy.Progression.Policy do
  @moduledoc "Pure XP threshold policy. It grants no XP and defines no reward curve."

  @max_xp 9_223_372_036_854_775_807

  def validate_thresholds([%{level: 1, required_xp: 0} | _] = thresholds) do
    valid? =
      thresholds
      |> Enum.with_index(1)
      |> Enum.reduce_while(-1, fn {entry, expected_level}, previous_xp ->
        case entry do
          %{level: ^expected_level, required_xp: xp}
          when is_integer(xp) and xp > previous_xp and xp <= @max_xp ->
            {:cont, xp}

          _ ->
            {:halt, :invalid}
        end
      end)

    if valid? == :invalid, do: {:error, :invalid_thresholds}, else: :ok
  end

  def validate_thresholds(_), do: {:error, :invalid_thresholds}

  def level_for(xp, thresholds) when is_integer(xp) and xp >= 0 and xp <= @max_xp do
    with :ok <- validate_thresholds(thresholds) do
      current = Enum.reduce_while(thresholds, hd(thresholds), fn threshold, level ->
        if threshold.required_xp <= xp, do: {:cont, threshold}, else: {:halt, level}
      end)

      next = Enum.find(thresholds, &(&1.level == current.level + 1))

      {:ok,
       %{level: current.level, xp: xp, level_start_xp: current.required_xp,
         next_level_xp: if(next, do: next.required_xp, else: nil)}}
    end
  end

  def level_for(_, _), do: {:error, :invalid_xp}
end
