defmodule ToriEconomy.Discord.NostrumConsumer do
  @moduledoc "Opt-in preview gateway. Never registers or handles existing JDA commands."
  use Nostrum.Consumer
  alias ToriEconomy.Discord.Adapter

  def handle_event({:INTERACTION_CREATE, interaction, _state}) do
    case Adapter.handle_component(interaction) do
      {:ok, content} -> update_shop_page(interaction, content)
      :ignore ->
        case Adapter.handle(interaction) do
          {:ok, content} -> respond(interaction, content)
          {:error, content} -> respond(interaction, content)
          :ignore -> :ignore
        end
    end
  end

  defp respond(interaction, content) do
    navigation = Adapter.shop_navigation(interaction)
    Nostrum.Api.Interaction.create_response(interaction, response(4, content, navigation))
  end

  defp update_shop_page(interaction, content) do
    navigation = Adapter.shop_navigation(interaction)
    Nostrum.Api.Interaction.create_response(interaction, response(7, content, navigation))
  end

  defp response(type, content, navigation) do
    components = case navigation do
      {_, category, page} ->
        {current, total} = page_bounds(content, page + 1)
        [button_row(category, current - 1, total)]
      _ -> []
    end
    data = %{
      embeds: [%{title: "Tori · Preview", description: content, color: 0xC5A15A,
                 footer: %{text: "Private test-guild preview"}}],
      components: components,
      allowed_mentions: %{parse: []}
    }
    data = if type == 4, do: Map.put(data, :flags, 64), else: data
    %{type: type, data: data}
  end

  defp button_row(category, page, total_pages) do
    next_page = min(total_pages - 1, page + 1)
    %{type: 1, components: [
      %{type: 2, style: 2, label: "Previous", custom_id: "tori-shop-page:#{category}:#{max(0, page - 1)}", disabled: page == 0},
      %{type: 2, style: 2, label: "Next", custom_id: "tori-shop-page:#{category}:#{next_page}", disabled: page + 1 >= total_pages}
    ]}
  end

  defp page_bounds(content, fallback) do
    case Regex.run(~r/page\s+(\d+)\s*\/\s*(\d+)/i, content) do
      [_, current, total] -> {String.to_integer(current), String.to_integer(total)}
      _ -> {fallback, fallback}
    end
  end
end
