defmodule ToriEconomy.Discord.NostrumConsumer do
  @moduledoc "Opt-in preview gateway. Never registers or handles existing JDA commands."
  use Nostrum.Consumer
  alias ToriEconomy.Discord.Adapter

  def handle_event({:INTERACTION_CREATE, interaction, _state}) do
    case Adapter.handle(interaction) do
      {:ok, content} -> respond(interaction, content)
      {:error, content} -> respond(interaction, content)
      :ignore -> :ignore
    end
  end

  defp respond(interaction, content) do
    Nostrum.Api.Interaction.create_response(interaction,
      %{type: 4, data: %{content: content, flags: 64}})
  end
end
