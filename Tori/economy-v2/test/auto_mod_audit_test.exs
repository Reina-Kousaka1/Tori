defmodule ToriEconomy.AutoModAuditTest do
  use ExUnit.Case, async: false
  alias ToriEconomy.{Sql, TestSchema}
  alias ToriEconomy.AutoMod.Audit

  @url System.get_env("TORI_ECONOMY_TEST_DATABASE_URL")
  @moduletag skip: if(is_nil(@url), do: "requires explicit isolated *_test database", else: false)

  setup_all do
    TestSchema.ensure_target!(@url)
    :ok
  end

  test "automatic detection records only observed facts in the shared moderation cases" do
    guild = Integer.to_string(8_000_000_000_000_000_000 + :rand.uniform(999_999_999_999_999_999))
    detection = %{
      "rule" => "join_burst",
      "target_user_id" => "123456789012345678",
      "channel_id" => nil,
      "observed_count" => 8,
      "escalation" => "review"
    }

    assert :ok = Audit.record(guild, detection)

    assert [[action, result, reason, status]] =
             Sql.query!(
               "SELECT action,result,reason,webhook_status FROM moderation_cases WHERE guild_id=$1",
               [guild]
             ).rows

    assert action == "AUTO_DETECT"
    assert result == "OBSERVED"
    assert reason == "join_burst: observed 8 event(s); escalation review"
    assert status == "NOT_SENT"
  end
end
