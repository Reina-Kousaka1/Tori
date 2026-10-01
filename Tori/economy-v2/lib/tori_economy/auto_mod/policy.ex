defmodule ToriEconomy.AutoMod.Policy do
  @moduledoc "Bounded detection thresholds. A detection never implies a Discord sanction."

  @defaults %{
    join_limit: 8,
    join_window_ms: 10_000,
    flood_limit: 5,
    flood_window_ms: 5_000,
    mention_limit: 6,
    block_invites: true,
    escalation: %{join_burst: :review, flood: :review, mentions: :review, invite: :review},
    allow_users: MapSet.new(),
    allow_channels: MapSet.new()
  }

  def defaults, do: @defaults

  def validate(changes) when is_map(changes) do
    config = Map.merge(@defaults, changes)

    if Enum.all?(Map.keys(changes), &Map.has_key?(@defaults, &1)) and
         is_integer(config.join_limit) and config.join_limit in 2..100 and
         is_integer(config.join_window_ms) and config.join_window_ms in 1_000..60_000 and
         is_integer(config.flood_limit) and config.flood_limit in 2..50 and
         is_integer(config.flood_window_ms) and config.flood_window_ms in 1_000..60_000 and
         is_integer(config.mention_limit) and config.mention_limit in 2..50 and
         is_boolean(config.block_invites) and
         is_map(config.escalation) and
         Enum.sort(Map.keys(config.escalation)) == [:flood, :invite, :join_burst, :mentions] and
         Enum.all?(Map.values(config.escalation), &(&1 in [:alert, :review])) and
         match?(%MapSet{}, config.allow_users) and
         match?(%MapSet{}, config.allow_channels) do
      {:ok, config}
    else
      {:error, :invalid_policy}
    end
  end

  def validate(_), do: {:error, :invalid_policy}

  def invite?(content) when is_binary(content),
    do: Regex.match?(~r/(?:discord\.gg|discord(?:app)?\.com\/invite)\/[a-z0-9-]+/i, content)

  def invite?(_), do: false
end
