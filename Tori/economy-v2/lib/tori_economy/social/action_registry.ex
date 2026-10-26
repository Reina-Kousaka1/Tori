defmodule ToriEconomy.Social.ActionRegistry do
  @moduledoc "Central definitions for the Discord social actions owned by Elixir."

  @cooldown_ms 24 * 60 * 60 * 1_000
  @actions ~w(hug kiss pat cuddle poke slap bite tickle highfive boop wave)

  @definitions Map.new(@actions, fn action ->
                 {action,
                  %{
                    id: action,
                    command_name: action,
                    target_required: true,
                    allow_self: false,
                    cooldown_policy: :actor_target_action,
                    cooldown_ms: @cooldown_ms,
                    social_effect: :none,
                    result_type: "social_action"
                  }}
               end)

  def all, do: Map.values(@definitions) |> Enum.sort_by(& &1.id)
  def command_names, do: @actions

  def fetch(action_id) when is_binary(action_id) do
    case Map.fetch(@definitions, action_id) do
      {:ok, definition} -> {:ok, definition}
      :error -> {:error, "UNKNOWN_ACTION"}
    end
  end

  def fetch(_), do: {:error, "UNKNOWN_ACTION"}

  def validate_target(action_id, actor_user_id, target_user_id) do
    with {:ok, definition} <- fetch(action_id),
         :ok <- required_target(definition, target_user_id),
         :ok <- self_target(definition, actor_user_id, target_user_id) do
      :ok
    end
  end

  def cooldown_ms, do: @cooldown_ms

  defp required_target(%{target_required: true}, target) when is_binary(target), do: :ok
  defp required_target(%{target_required: true}, _), do: {:error, "INVALID_TARGET"}
  defp required_target(_, _), do: :ok

  defp self_target(%{allow_self: false}, actor, target) when actor == target,
    do: {:error, "INVALID_TARGET"}

  defp self_target(_, _, _), do: :ok
end
