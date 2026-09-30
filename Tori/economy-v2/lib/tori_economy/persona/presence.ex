defmodule ToriEconomy.Persona.Presence do
  @moduledoc """
  Global, transient Tori activity context.
  This process stores semantic state only; Discord activity text and Presence remain Java-owned.
  """
  use GenServer

  @activities ~w(general school ballet volleyball cheer resting)

  defstruct activity: "general",
            special_event: nil,
            revision: 0,
            updated_at_ms: 0,
            expires_at_ms: nil

  def start_link(opts) do
    name = Keyword.get(opts, :name, __MODULE__)
    state = %__MODULE__{
      updated_at_ms: Keyword.get(opts, :now_ms, System.system_time(:millisecond))
    }
    GenServer.start_link(__MODULE__, state, if(name, do: [name: name], else: []))
  end

  def snapshot(server \\ __MODULE__, now_ms \\ System.system_time(:millisecond)) do
    GenServer.call(server, {:snapshot, now_ms})
  end

  def set_context(activity, opts \\ [])

  def set_context(activity, opts) when is_binary(activity) and is_list(opts) do
    special_event = Keyword.get(opts, :special_event)
    ttl_seconds = Keyword.get(opts, :ttl_seconds)

    with true <- activity in @activities,
         :ok <- validate_special_event(special_event),
         :ok <- validate_ttl(ttl_seconds) do
      server = Keyword.get(opts, :server, __MODULE__)
      now_ms = Keyword.get(opts, :now_ms, System.system_time(:millisecond))
      GenServer.call(server, {:set_context, activity, special_event, ttl_seconds, now_ms})
    else
      _ -> {:error, :invalid}
    end
  end

  def set_context(_activity, _opts), do: {:error, :invalid}

  def reset(server \\ __MODULE__, now_ms \\ System.system_time(:millisecond)) do
    GenServer.call(server, {:reset, now_ms})
  end

  def activities, do: @activities

  defp validate_special_event(nil), do: :ok
  defp validate_special_event(value) when is_binary(value) do
    if byte_size(value) <= 64 and Regex.match?(~r/^[A-Za-z0-9_-]+$/, value),
      do: :ok,
      else: {:error, :invalid}
  end
  defp validate_special_event(_), do: {:error, :invalid}

  defp validate_ttl(nil), do: :ok
  defp validate_ttl(value) when is_integer(value) and value in 60..604_800, do: :ok
  defp validate_ttl(_), do: {:error, :invalid}

  @impl true
  def init(state), do: {:ok, state}

  @impl true
  def handle_call({:snapshot, now_ms}, _from, state) do
    state = expire_if_needed(state, now_ms)
    {:reply, public_state(state), state}
  end

  def handle_call({:set_context, activity, special_event, ttl_seconds, now_ms}, _from, state) do
    next = %__MODULE__{
      activity: activity,
      special_event: special_event,
      revision: state.revision + 1,
      updated_at_ms: now_ms,
      expires_at_ms: if(ttl_seconds, do: now_ms + ttl_seconds * 1_000, else: nil)
    }

    {:reply, {:ok, public_state(next)}, next}
  end

  def handle_call({:reset, now_ms}, _from, state) do
    next = %__MODULE__{
      activity: "general",
      revision: state.revision + 1,
      updated_at_ms: now_ms
    }

    {:reply, public_state(next), next}
  end

  defp expire_if_needed(%__MODULE__{expires_at_ms: expires} = state, now_ms)
       when is_integer(expires) and now_ms >= expires do
    %__MODULE__{
      activity: "general",
      revision: state.revision + 1,
      updated_at_ms: now_ms
    }
  end

  defp expire_if_needed(state, _now_ms), do: state

  defp public_state(state) do
    %{
      activity: state.activity,
      special_event: state.special_event,
      revision: state.revision,
      updated_at_ms: state.updated_at_ms,
      expires_at_ms: state.expires_at_ms
    }
  end
end
