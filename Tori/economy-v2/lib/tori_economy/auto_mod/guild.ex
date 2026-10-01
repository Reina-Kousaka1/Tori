defmodule ToriEconomy.AutoMod.Guild do
  @moduledoc "Transient, isolated detection windows for one Discord guild."
  use GenServer
  alias ToriEconomy.AutoMod.Policy

  def start_link(opts) do
    case Keyword.get(opts, :name) do
      nil -> GenServer.start_link(__MODULE__, opts)
      name -> GenServer.start_link(__MODULE__, opts, name: name)
    end
  end

  def observe_join(server, user_id, at_ms),
    do: GenServer.call(server, {:join, user_id, at_ms})

  def observe_message(server, user_id, channel_id, event_id, content, mentions, at_ms),
    do: GenServer.call(server, {:message, user_id, channel_id, event_id, content, mentions, at_ms})

  def configure(server, changes), do: GenServer.call(server, {:configure, changes})
  def override(server, user_id, allowed?), do: GenServer.call(server, {:override, user_id, allowed?})

  @impl true
  def init(opts) do
    {:ok, policy} = Policy.validate(Keyword.get(opts, :policy, %{}))
    {:ok, %{policy: policy, joins: [], join_alerted: false, messages: %{}, seen: %{}}}
  end

  @impl true
  def handle_call({:configure, changes}, _from, state) when is_map(changes) do
    case Policy.validate(Map.merge(state.policy, changes)) do
      {:ok, policy} -> {:reply, :ok, %{state | policy: policy}}
      error -> {:reply, error, state}
    end
  end

  def handle_call({:configure, _changes}, _from, state),
    do: {:reply, {:error, :invalid_policy}, state}

  def handle_call({:override, user, allowed?}, _from, state) when is_boolean(allowed?) do
    allow =
      if allowed?,
        do: MapSet.put(state.policy.allow_users, user),
        else: MapSet.delete(state.policy.allow_users, user)

    {:reply, :ok, %{state | policy: %{state.policy | allow_users: allow}}}
  end

  def handle_call({:join, user, now}, _from, state) do
    policy = state.policy
    recent = Enum.filter(state.joins, &(&1 > now - policy.join_window_ms))
    joins = Enum.take([now | recent], 1_000)
    alerted = state.join_alerted and recent != []

    decision =
      if not MapSet.member?(policy.allow_users, user) and
           length(joins) >= policy.join_limit and not alerted do
        decision(:join_burst, user, nil, length(joins), policy)
      end

    {:reply, decision, %{state | joins: joins, join_alerted: alerted or decision != nil}}
  end

  def handle_call({:message, user, channel, event_id, content, mentions, now}, _from, state) do
    policy = state.policy
    seen = Map.filter(state.seen, fn {_id, at} -> at > now - policy.flood_window_ms end)

    if Map.has_key?(seen, event_id) do
      {:reply, nil, %{state | seen: seen}}
    else
      messages =
        state.messages
        |> Enum.map(fn {id, entry} ->
          {id, %{entry | times: Enum.filter(entry.times, &(&1 > now - policy.flood_window_ms))}}
        end)
        |> Enum.reject(fn {_id, entry} -> entry.times == [] end)
        |> Map.new()

      entry = Map.get(messages, user, %{times: [], alerted: false})
      times = Enum.take([now | entry.times], 100)
      flood_alerted = entry.alerted and entry.times != []

      decision =
        cond do
          MapSet.member?(policy.allow_users, user) or
              MapSet.member?(policy.allow_channels, channel) ->
            nil

          mentions >= policy.mention_limit ->
            decision(:mentions, user, channel, mentions, policy)

          policy.block_invites and Policy.invite?(content) ->
            decision(:invite, user, channel, 1, policy)

          length(times) >= policy.flood_limit and not flood_alerted ->
            decision(:flood, user, channel, length(times), policy)

          true ->
            nil
        end

      flooded = flood_alerted or (decision != nil and decision["rule"] == "flood")

      {:reply, decision,
       %{
         state
         | messages: Map.put(messages, user, %{times: times, alerted: flooded}),
           seen: if(map_size(seen) < 10_000, do: Map.put(seen, event_id, now), else: %{event_id => now})
       }}
    end
  end

  defp decision(rule, user, channel, count, policy) do
    %{
      "rule" => Atom.to_string(rule),
      "target_user_id" => to_string(user),
      "channel_id" => if(channel, do: to_string(channel), else: nil),
      "observed_count" => count,
      "escalation" => Atom.to_string(Map.fetch!(policy.escalation, rule))
    }
  end
end
