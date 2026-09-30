defmodule ToriEconomy.Persona.PresenceTest do
  use ExUnit.Case, async: true
  alias ToriEconomy.Persona.Presence

  test "global activity is transient, versioned state with optional expiry" do
    {:ok, server} = Presence.start_link(name: nil, now_ms: 1_000)
    on_exit(fn -> GenServer.stop(server) end)

    assert %{activity: "general", revision: 0, special_event: nil} =
             Presence.snapshot(server, 1_000)

    assert {:ok, ballet} =
             Presence.set_context("ballet", server: server, now_ms: 2_000,
               special_event: "recital", ttl_seconds: 60)

    assert ballet.activity == "ballet"
    assert ballet.special_event == "recital"
    assert ballet.revision == 1
    assert ballet.updated_at_ms == 2_000
    assert ballet.expires_at_ms == 62_000

    assert %{activity: "general", special_event: nil, revision: 2, updated_at_ms: 62_000} =
             Presence.snapshot(server, 62_000)
  end

  test "unknown activities and invalid events or lifetimes are rejected" do
    {:ok, server} = Presence.start_link(name: nil, now_ms: 1_000)
    on_exit(fn -> GenServer.stop(server) end)

    assert {:error, :invalid} = Presence.set_context("individual_user_career", server: server)
    assert {:error, :invalid} = Presence.set_context("cheer", server: server,
      special_event: "event with spaces")
    assert {:error, :invalid} = Presence.set_context("volleyball", server: server,
      ttl_seconds: 59)
    assert Presence.snapshot(server, 1_000).revision == 0
  end

  test "a context without ttl remains active" do
    {:ok, server} = Presence.start_link(name: nil, now_ms: 1_000)
    on_exit(fn -> GenServer.stop(server) end)

    assert {:ok, %{activity: "school", expires_at_ms: nil}} =
             Presence.set_context("school", server: server, now_ms: 2_000)
    assert Presence.snapshot(server, 100_000).activity == "school"
  end
end
