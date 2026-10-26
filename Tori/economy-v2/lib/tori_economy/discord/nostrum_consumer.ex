defmodule ToriEconomy.Discord.NostrumConsumer do
  @moduledoc "Nostrum consumer for the Elixir-owned Tori commands."
  use Nostrum.Consumer
  require Logger
  alias ToriEconomy.Discord.Adapter

  def handle_event({:GUILD_MEMBER_ADD, {guild_id, member}, _state}) do
    user = Map.get(member, :user)

    if ToriEconomy.AutoMod.enabled?() and is_integer(guild_id) and is_map(user) and
         is_integer(Map.get(user, :id)) and Map.get(user, :bot) != true do
      ToriEconomy.AutoMod.observe_join(guild_id, user.id)
    end
  end

  def handle_event({:MESSAGE_CREATE, message, _state}) do
    author = Map.get(message, :author)
    guild_id = Map.get(message, :guild_id)

    if ToriEconomy.AutoMod.enabled?() and is_integer(guild_id) and is_map(author) and
         is_integer(Map.get(author, :id)) and Map.get(author, :bot) != true do
      ToriEconomy.AutoMod.observe_message(
        guild_id,
        author.id,
        Map.get(message, :channel_id),
        Map.get(message, :id),
        Map.get(message, :content) || "",
        length(Map.get(message, :mentions) || [])
      )
    end
  end

  def handle_event({:INTERACTION_CREATE, interaction, _state}) do
    if Adapter.supported_interaction?(interaction) do
      acknowledge_and_finish(interaction)
    else
      :ignore
    end
  end

  defp acknowledge_and_finish(interaction) do
    case Nostrum.Api.Interaction.create_response(interaction, acknowledgement(interaction)) do
      :ok -> finish_interaction(interaction)
      {:ok} -> finish_interaction(interaction)
      {:ok, _} -> finish_interaction(interaction)
      _ -> :ok
    end
  rescue
    _ -> Logger.warning("Could not acknowledge a Tori main-bot interaction")
  catch
    _, _ -> Logger.warning("Could not acknowledge a Tori main-bot interaction")
  end

  def acknowledgement(interaction) do
    if Adapter.component_interaction?(interaction) do
      %{type: 6}
    else
      %{type: 5, data: %{flags: 64}}
    end
  end

  @doc false
  def response_data(content, navigation) do
    components =
      case navigation do
        {_, category, page} ->
          {current, total} = page_bounds(content, page + 1)
          [category_row(category), button_row(category, current - 1, total)]

        _ ->
          []
      end

    %{
      embeds: [
        %{
          title: "Tori",
          description: content,
          color: 0xC5A15A,
          footer: %{text: "Tori economy"}
        }
      ],
      components: components,
      allowed_mentions: %{parse: []}
    }
  end

  defp finish_interaction(interaction) do
    result =
      case Adapter.handle_component(interaction) do
        {:ok, content} -> {:ok, content}
        :ignore -> Adapter.handle(interaction)
      end

    case result do
      {:ok, content} -> edit_interaction(interaction, content)
      {:error, content} -> edit_interaction(interaction, content)
      :ignore -> edit_interaction(interaction, "This Tori command is not available.")
    end
  rescue
    _ ->
      Logger.warning("Tori main-bot command request failed")
      safe_edit_interaction(interaction, "This Tori command is currently unavailable.")
  catch
    _, _ ->
      Logger.warning("Tori main-bot command request failed")
      safe_edit_interaction(interaction, "This Tori command is currently unavailable.")
  end

  defp edit_interaction(interaction, content) do
    navigation = Adapter.shop_navigation(interaction)
    Nostrum.Api.Interaction.edit_response(interaction, response_data(content, navigation))
  end

  defp safe_edit_interaction(interaction, content) do
    edit_interaction(interaction, content)
  rescue
    _ -> :ok
  catch
    _, _ -> :ok
  end

  defp category_row(selected) do
    %{
      type: 1,
      components: [
        %{
          type: 3,
          custom_id: "tori-shop-category",
          placeholder: "Browse a category",
          min_values: 1,
          max_values: 1,
          options:
            Enum.map(Adapter.shop_categories(), fn {label, value} ->
              %{label: label, value: value, default: value == selected}
            end)
        }
      ]
    }
  end

  defp button_row(category, page, total_pages) do
    next_page = min(total_pages - 1, page + 1)

    %{
      type: 1,
      components: [
        %{
          type: 2,
          style: 2,
          label: "Previous",
          custom_id: "tori-shop-page:#{category}:#{max(0, page - 1)}",
          disabled: page == 0
        },
        %{
          type: 2,
          style: 2,
          label: "Next",
          custom_id: "tori-shop-page:#{category}:#{next_page}",
          disabled: page + 1 >= total_pages
        }
      ]
    }
  end

  defp page_bounds(content, fallback) do
    case Regex.run(~r/page\s+(\d+)\s*\/\s*(\d+)/i, content) do
      [_, current, total] -> {String.to_integer(current), String.to_integer(total)}
      _ -> {fallback, fallback}
    end
  end
end
