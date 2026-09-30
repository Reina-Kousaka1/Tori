defmodule ToriEconomy.Discord.Gateway do
  @moduledoc "Starts the Nostrum consumer for Tori's main Discord bot."
  use GenServer

  alias ToriEconomy.Discord.NostrumConsumer

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts)

  @impl true
  def init(_opts) do
    Process.flag(:trap_exit, true)

    with :ok <- Application.put_env(:nostrum, :gateway_intents, [:guilds]),
         {:ok, started_apps} <- Application.ensure_all_started(:nostrum) do
      # Nostrum 0.10 provides the Consumer child as a GenServer start_link/1 callback.
      case NostrumConsumer.start_link(name: NostrumConsumer) do
        {:ok, consumer} ->
          {:ok, %{started_apps: started_apps, consumer: consumer}}

        {:error, _reason} ->
          stop_started_apps(started_apps)
          {:stop, {:nostrum_gateway_start_failed, :consumer_start_failed}}
      end
    else
      {:error, _reason} -> {:stop, {:nostrum_gateway_start_failed, :startup_failed}}
    end
  rescue
    _ -> {:stop, {:nostrum_gateway_start_failed, :startup_failed}}
  catch
    _, _ -> {:stop, {:nostrum_gateway_start_failed, :startup_failed}}
  end

  @impl true
  def handle_info({:EXIT, consumer, reason}, %{consumer: consumer} = state) do
    {:stop, {:nostrum_consumer_exit, reason}, state}
  end

  def handle_info(_message, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, %{consumer: consumer, started_apps: started_apps}) do
    if Process.alive?(consumer), do: Process.exit(consumer, :shutdown)
    stop_started_apps(started_apps)
    :ok
  end

  def terminate(_reason, _state), do: :ok

  defp stop_started_apps(started_apps),
    do: Enum.each(Enum.reverse(started_apps), &Application.stop/1)
end
