defmodule ToriEconomy.Discord.Adapter do
  @moduledoc "Nostrum-independent, read-only interaction mapping. No command is registered here."
  alias ToriEconomy.{Contract, Dispatcher}

  @preview_command "tori-profile-preview"

  def handle(%{data: %{name: @preview_command}, id: id, guild_id: guild,
               channel_id: channel} = interaction)
      when is_integer(id) and is_integer(guild) and is_integer(channel) do
    with {:ok, user} <- actor_id(interaction),
         {:ok, request} <- Contract.validate(%{
           "request_id" => Ecto.UUID.generate(), "idempotency_key" => nil,
           "operation" => "profile.snapshot",
           "context" => %{"actor_user_id" => user, "guild_id" => Integer.to_string(guild),
                          "channel_id" => Integer.to_string(channel)}, "args" => %{}
         }),
         {:ok, %{"status" => "ok", "result" => profile}} <- Dispatcher.execute(request) do
      {:ok, "Profile • #{profile["user_id"]} • #{profile["balance"]} Credits"}
    else
      _ -> {:error, "Profile is currently unavailable."}
    end
  end

  def handle(_), do: :ignore

  defp actor_id(%{member: %{user: %{id: id}}}) when is_integer(id), do: {:ok, Integer.to_string(id)}
  defp actor_id(%{user: %{id: id}}) when is_integer(id), do: {:ok, Integer.to_string(id)}
  defp actor_id(_), do: {:error, :missing_actor}
end
