defmodule ToriEconomy.MixProject do
  use Mix.Project

  def project do
    [
      app: :tori_economy,
      version: "0.1.0",
      elixir: "~> 1.18",
      start_permanent: Mix.env() == :prod,
      deps: deps()
    ]
  end

  def application do
    [extra_applications: [:logger, :crypto], mod: {ToriEconomy.Application, []}]
  end

  defp deps do
    [
      {:ecto_sql, "~> 3.14"},
      {:postgrex, "~> 0.22.4"},
      {:plug_cowboy, "~> 2.9"},
      {:jason, "~> 1.4.5"}
    ]
  end
end
