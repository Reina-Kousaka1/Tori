defmodule ToriEconomy.Discord.Config do
  @moduledoc false

  def load(env \\ System.get_env()) when is_map(env) do
    if Map.get(env, "TORI_NOSTRUM_ENABLED") == "true" do
      with {:ok, token} <- required(env, "DISCORD_TOKEN"),
           {:ok, guild_id} <- required(env, "DISCORD_GUILD_ID"),
           :ok <- validate_snowflake(guild_id) do
        {:enabled, %{token: token, guild_id: guild_id}}
      end
    else
      :disabled
    end
  end

  def load!(env \\ System.get_env()) do
    case load(env) do
      {:enabled, _config} = enabled ->
        enabled

      :disabled ->
        :disabled

      {:error, message} ->
        raise ArgumentError, message
    end
  end

  defp required(env, name) do
    case Map.get(env, name) do
      value when is_binary(value) ->
        value = String.trim(value)

        if value == "" do
          {:error, "#{name} is required when TORI_NOSTRUM_ENABLED=true"}
        else
          {:ok, value}
        end

      _ ->
        {:error, "#{name} is required when TORI_NOSTRUM_ENABLED=true"}
    end
  end

  defp validate_snowflake(value) do
    if Regex.match?(~r/^[0-9]{17,20}$/, value) do
      :ok
    else
      {:error, "DISCORD_GUILD_ID must be a 17–20 digit Discord server ID"}
    end
  end
end
