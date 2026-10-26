defmodule ToriEconomy.AutoMod.Supervisor do
  @moduledoc "One transient detector per guild under a dynamic supervisor."
  use Supervisor

  def start_link(opts \\ []), do: Supervisor.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(_opts) do
    Supervisor.init(
      [
        {Registry, keys: :unique, name: ToriEconomy.AutoMod.Registry},
        {DynamicSupervisor, strategy: :one_for_one, name: ToriEconomy.AutoMod.Guilds}
      ],
      strategy: :one_for_one
    )
  end
end
