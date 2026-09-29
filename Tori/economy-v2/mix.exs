defmodule ToriEconomy.MixProject do
  use Mix.Project

  def project do
    [
      app: :tori_economy,
      version: "0.1.0",
      elixir: "~> 1.18",
      start_permanent: Mix.env() == :prod,
      releases: [tori_economy: [applications: [nostrum: :load]]],
      deps: deps()
    ]
  end

  def application do
    [extra_applications: [:logger, :crypto, :certifi, :gun, :inets, :jason, :mime],
     mod: {ToriEconomy.Application, []}]
  end

  defp deps do
    [
      {:ecto_sql, "~> 3.14"},
      {:postgrex, "~> 0.22.4"},
      {:bandit, "~> 1.12.5"},
      {:jason, "~> 1.4.5"},
      {:nostrum, "~> 0.10.4", runtime: false}
    ]
  end
end
