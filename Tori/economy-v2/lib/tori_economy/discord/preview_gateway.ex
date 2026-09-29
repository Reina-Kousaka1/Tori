defmodule ToriEconomy.Discord.PreviewGateway do
  @moduledoc "Starts Nostrum only after the supervised Repo is verified against an explicit test database."
  use GenServer

  alias Ecto.Adapters.SQL
  alias ToriEconomy.Discord.{NostrumConsumer, PreviewCommands}
  alias ToriEconomy.Repo

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts)

  @impl true
  def init(_opts) do
    Process.flag(:trap_exit, true)

    with :ok <- verify_test_database(),
         token <- System.fetch_env!("TORI_NOSTRUM_TOKEN"),
         :ok <- Nostrum.Token.check_token!(token),
         :ok <- Application.put_env(:nostrum, :token, token),
         :ok <- Application.put_env(:nostrum, :gateway_intents, [:guilds]),
         {:ok, started_apps} <- Application.ensure_all_started(:nostrum) do
      case NostrumConsumer.start_link() do
        {:ok, consumer} ->
          {:ok, %{started_apps: started_apps, consumer: consumer}}

        {:error, reason} ->
          stop_started_apps(started_apps)
          {:stop, {:preview_gateway_start_failed, reason}}
      end
    else
      {:error, reason} -> {:stop, {:preview_gateway_start_failed, reason}}
    end
  rescue
    error -> {:stop, {:preview_gateway_start_failed, error}}
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

  defp verify_test_database do
    mode = System.get_env("TORI_ECONOMY_WRITE_MODE")
    configured = PreviewCommands.configured_database(Repo.config())
    [[connected]] = SQL.query!(Repo, "SELECT current_database()", []).rows

    if PreviewCommands.safe_test_database?(mode, configured, connected) do
      :ok
    else
      {:error, :test_database_required}
    end
  end

  defp stop_started_apps(started_apps),
    do: Enum.each(Enum.reverse(started_apps), &Application.stop/1)
end
