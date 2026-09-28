defmodule ToriEconomy.Sql do
  @moduledoc false
  def query!(statement, params \\ []) do
    Ecto.Adapters.SQL.query!(ToriEconomy.Repo, statement, params)
  end
end
