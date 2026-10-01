defmodule ToriEconomy.AutoMod do
  @moduledoc "Opt-in Nostrum detection; Java's existing manual moderation remains untouched."
  alias ToriEconomy.AutoMod.{Audit, Guild}

  def enabled?, do: System.get_env("TORI_AUTOMOD_ENABLED") == "true"

  def observe_join(guild_id, user_id) do
    observe(guild_id, fn server ->
      Guild.observe_join(server, user_id, System.monotonic_time(:millisecond))
    end)
  end

  def observe_message(guild_id, user_id, channel_id, event_id, content, mention_count) do
    observe(guild_id, fn server ->
      Guild.observe_message(
        server,
        user_id,
        channel_id,
        event_id,
        content,
        mention_count,
        System.monotonic_time(:millisecond)
      )
    end)
  end

  def configure(guild_id, changes) do
    with {:ok, server} <- guild_server(guild_id), do: Guild.configure(server, changes)
  end

  def override(guild_id, user_id, allowed?) do
    with {:ok, server} <- guild_server(guild_id),
         do: Guild.override(server, user_id, allowed?)
  end

  defp observe(guild_id, fun) do
    if enabled?() do
      with {:ok, server} <- guild_server(guild_id) do
        case fun.(server) do
          nil -> :ignore
          detection -> Audit.record(guild_id, detection)
        end
      end
    else
      :ignore
    end
  rescue
    _ -> {:error, :detection_unavailable}
  catch
    :exit, _ -> {:error, :detection_unavailable}
  end

  defp guild_server(guild_id) when is_integer(guild_id) do
    key = {:guild, guild_id}

    case Registry.lookup(ToriEconomy.AutoMod.Registry, key) do
      [{pid, _}] ->
        {:ok, pid}

      [] ->
        via = {:via, Registry, {ToriEconomy.AutoMod.Registry, key}}

        case DynamicSupervisor.start_child(
               ToriEconomy.AutoMod.Guilds,
               {Guild, guild_id: guild_id, name: via}
             ) do
          {:ok, pid} -> {:ok, pid}
          {:error, {:already_started, pid}} -> {:ok, pid}
          _ -> {:error, :detection_unavailable}
        end
    end
  end

  defp guild_server(_), do: {:error, :detection_unavailable}
end
