defmodule ToriEconomy.Persona.Mood do
  @moduledoc "Global, transient mood state. Economy outcomes never depend on it."
  use GenServer

  @moods ~w(normal happy excited playful competitive focused sleepy annoyed chaotic)a
  @events %{
    rare_drop: {:excited, 0.75, 3_600_000, 900_000},
    successful_activity: {:happy, 0.45, 1_800_000, 300_000},
    level_up: {:excited, 0.8, 3_600_000, 3_600_000},
    ballet_practice: {:focused, 0.5, 3_600_000, 900_000},
    volleyball_match: {:competitive, 0.65, 3_600_000, 900_000},
    cheer_event: {:playful, 0.5, 2_700_000, 900_000},
    late_night: {:sleepy, 0.35, 1_800_000, 900_000},
    system_failure: {:annoyed, 0.2, 900_000, 900_000},
    celebration: {:chaotic, 0.55, 1_800_000, 1_800_000}
  }

  defstruct mood: :normal,
            intensity: 0.0,
            started_at_ms: 0,
            expires_at_ms: nil,
            reason: :initial,
            recent_events: %{}

  def start_link(opts) do
    name = Keyword.get(opts, :name, __MODULE__)
    initial = new(Keyword.get(opts, :now_ms, System.system_time(:millisecond)))
    GenServer.start_link(__MODULE__, initial, if(name, do: [name: name], else: []))
  end

  def new(now_ms) when is_integer(now_ms), do: %__MODULE__{started_at_ms: now_ms}

  def snapshot(server \\ __MODULE__, now_ms \\ System.system_time(:millisecond)),
    do: GenServer.call(server, {:snapshot, now_ms})

  def record(event, server \\ __MODULE__, now_ms \\ System.system_time(:millisecond)),
    do: GenServer.call(server, {:record, event, now_ms})

  def reset(server \\ __MODULE__, now_ms \\ System.system_time(:millisecond)),
    do: GenServer.call(server, {:reset, now_ms})

  def event_from_name(name) when is_binary(name) do
    Enum.find_value(@events, :error, fn {event, _} ->
      if Atom.to_string(event) == name, do: {:ok, event}, else: false
    end)
  end

  def event_from_name(_), do: :error

  def resolve(%__MODULE__{} = state, now_ms) when is_integer(now_ms) do
    if state.expires_at_ms && now_ms >= state.expires_at_ms do
      %{new(now_ms) | recent_events: state.recent_events}
    else
      remaining =
        if state.expires_at_ms do
          duration = state.expires_at_ms - state.started_at_ms
          min(1.0, max(0.0, (state.expires_at_ms - now_ms) / duration))
        else
          1.0
        end

      %{state | intensity: state.intensity * remaining}
    end
  end

  def transition(%__MODULE__{} = state, event, now_ms) when is_integer(now_ms) do
    case Map.fetch(@events, event) do
      {:ok, {mood, intensity, duration, cooldown}} ->
        state = resolve(state, now_ms)
        last_event_at = Map.get(state.recent_events, event)

        if is_integer(last_event_at) and now_ms - last_event_at < cooldown do
          state
        else
          %__MODULE__{
            mood: mood,
            intensity: intensity,
            started_at_ms: now_ms,
            expires_at_ms: now_ms + duration,
            reason: event,
            recent_events: Map.put(state.recent_events, event, now_ms)
          }
        end

      :error ->
        state
    end
  end

  def moods, do: @moods

  @impl true
  def init(state), do: {:ok, state}

  @impl true
  def handle_call({:snapshot, now_ms}, _from, state), do: {:reply, resolve(state, now_ms), state}

  def handle_call({:record, event, now_ms}, _from, state) do
    next = transition(state, event, now_ms)
    {:reply, resolve(next, now_ms), next}
  end

  def handle_call({:reset, now_ms}, _from, _state) do
    next = new(now_ms)
    {:reply, next, next}
  end
end
