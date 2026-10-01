defmodule ToriEconomy.Discord.CommandsTest do
  use ExUnit.Case, async: true

  alias ToriEconomy.Discord.{Commands, Config}

  @application_id "123456789012345678"
  @guild_id "234567890123456789"
  @token "test-main-bot-token"
  @api "https://discord.com/api/v10"
  @guild_commands_url "#{@api}/applications/#{@application_id}/guilds/#{@guild_id}/commands"

  test "the registrar exposes only Elixir-owned commands" do
    assert Enum.sort(Enum.map(Commands.definitions(), & &1["name"])) ==
             ["career", "divorce", "marriage", "marry", "profile"]
  end

  test "shop opt-in uses the same guild registrar without taking Java commands" do
    definitions = Commands.definitions(true)
    assert Enum.sort(Enum.map(definitions, & &1["name"])) ==
             ["career", "divorce", "marriage", "marry", "profile", "shop"]

    shop = Enum.find(definitions, &(&1["name"] == "shop"))
    assert Enum.map(shop["options"], & &1["name"]) == ["catalog", "browse", "item", "buy"]

    buy = Enum.find(shop["options"], &(&1["name"] == "buy"))
    assert Enum.find(buy["options"], &(&1["name"] == "item_id"))["required"]
    assert Enum.find(buy["options"], &(&1["name"] == "quantity"))["type"] == 4
  end

  test "the configured guild registration preserves Java-owned commands" do
    {:enabled, config} =
      Config.load(%{
        "TORI_NOSTRUM_ENABLED" => "true",
        "DISCORD_TOKEN" => @token,
        "DISCORD_GUILD_ID" => @guild_id
      })

    existing_profile = %{"id" => "345678901234567890", "name" => "profile", "type" => 1}
    existing_java = %{"id" => "456789012345678901", "name" => "daily", "type" => 1}
    Process.put(:discord_requests, [])

    request = fn method, url, token, payload ->
      Process.put(
        :discord_requests,
        Process.get(:discord_requests) ++ [{method, url, token, payload}]
      )

      case {method, url} do
        {:get, "https://discord.com/api/v10/oauth2/applications/@me"} ->
          {:response, 200, %{"id" => @application_id}}

        {:get, @guild_commands_url} ->
          {:response, 200, [existing_profile, existing_java]}

        {:post, _url} ->
          {:response, 201, %{}}

        {:patch, _url} ->
          {:response, 200, %{}}
      end
    end

    assert :ok = Commands.register_commands(config.token, config.guild_id, request)

    requests = Process.get(:discord_requests)
    [application_request, list_request | updates] = requests
    collection = @guild_commands_url

    assert application_request == {
             :get,
             "https://discord.com/api/v10/oauth2/applications/@me",
             @token,
             nil
           }

    assert list_request == {:get, collection, @token, nil}
    assert length(updates) == 5

    registered_names =
      Enum.map(updates, fn {_method, _url, _token, payload} -> payload["name"] end)
    assert Enum.sort(registered_names) ==
             ["career", "divorce", "marriage", "marry", "profile"]

    assert Enum.any?(updates, fn {method, url, _token, payload} ->
             method == :patch and url == collection <> "/345678901234567890" and
               payload["name"] == "profile"
           end)

    assert Enum.any?(updates, fn {method, url, _token, payload} ->
             method == :post and url == collection and payload["name"] == "career"
           end)

    refute Enum.any?(updates, fn {_method, _url, _token, payload} ->
             payload["name"] == "daily"
           end)

    refute Enum.any?(requests, fn {method, _url, _token, _payload} ->
             method in [:put, :delete]
           end)
  end
end
