defmodule ToriEconomy.Progression do
  @moduledoc "Configured XP awards and career progression; no fabricated reward curve."
  alias ToriEconomy.{Idempotency, Sql}

  def execute(%{operation: "progression.snapshot"} = request) do
    user = request.context["target_user_id"] || request.context["actor_user_id"]
    xp = case Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows do
      [[value]] -> value
      [] -> 0
    end
    level = level_for(xp)
    careers = Sql.query!("""
      SELECT c.career_code,c.display_name,coalesce(p.xp,0) FROM economy_v2_careers c
      LEFT JOIN economy_v2_career_progress p ON p.career_code=c.career_code AND p.user_id=$1
      WHERE c.active ORDER BY c.career_code
    """, [user]).rows
    |> Enum.map(fn [code, name, career_xp] ->
      %{"code" => code, "name" => name, "xp" => Integer.to_string(career_xp)}
    end)
    {:ok, %{"request_id" => request.request_id, "status" => "ok",
            "result" => %{"type" => "progression", "user_id" => user,
                          "xp" => Integer.to_string(xp), "level" => level, "careers" => careers}}}
  end

  def execute(%{operation: "progression.grant"} = request) do
    Idempotency.run(request, fn -> grant(request) end,
      skip_persist_errors: ["REQUIREMENT_NOT_MET", "COOLDOWN_ACTIVE"])
  end

  defp grant(request) do
    user = request.context["actor_user_id"]
    source = request.args["source_code"]
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])
    case Sql.query!("SELECT reward_xp,cooldown_ms,career_code FROM economy_v2_xp_sources WHERE source_code=$1 AND active FOR SHARE", [source]).rows do
      [[reward, cooldown, career]] -> grant_configured(request, user, source, reward, cooldown, career)
      [] -> error("REQUIREMENT_NOT_MET")
    end
  end

  defp grant_configured(request, user, source, reward, cooldown, career) do
    [[elapsed]] = Sql.query!("""
      SELECT coalesce(extract(epoch from (now()-max(occurred_at)))*1000, 9223372036854775807)::bigint
      FROM economy_v2_xp_events WHERE user_id=$1 AND source_code=$2
    """, [user, source]).rows
    if elapsed < cooldown do
      error("COOLDOWN_ACTIVE")
    else
      Sql.query!("INSERT INTO economy_v2_account_progress(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
      [[old]] = Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1 FOR UPDATE", [user]).rows
      if old > 9_223_372_036_854_775_807 - reward do
        error("REQUIREMENT_NOT_MET")
      else
        next = old + reward
        Sql.query!("UPDATE economy_v2_account_progress SET xp=$2,updated_at=now() WHERE user_id=$1", [user, next])
        career_after = if career do
          Sql.query!("INSERT INTO economy_v2_career_progress(user_id,career_code) VALUES ($1,$2) ON CONFLICT DO NOTHING", [user, career])
          [[career_old]] = Sql.query!("SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code=$2 FOR UPDATE", [user, career]).rows
          if career_old > 9_223_372_036_854_775_807 - reward do
            ToriEconomy.Repo.rollback({:unpersisted_result, error("REQUIREMENT_NOT_MET")})
          else
            Sql.query!("UPDATE economy_v2_career_progress SET xp=$3 WHERE user_id=$1 AND career_code=$2", [user, career, career_old + reward])
            career_old + reward
          end
        end
        Sql.query!("""
          INSERT INTO economy_v2_xp_events
            (request_key,user_id,source_code,xp_delta,xp_after,career_code,career_xp_after)
          VALUES ($1,$2,$3,$4,$5,$6,$7)
        """, [request.idempotency_key, user, source, reward, next, career, career_after])
        %{"status" => "ok", "result" => %{"type" => "xp_grant", "source_code" => source,
          "xp_awarded" => Integer.to_string(reward), "xp" => Integer.to_string(next),
          "level" => level_for(next), "career_code" => career,
          "presentation_key" => "progression.grant.success"}}
      end
    end
  end

  defp level_for(xp) do
    case Sql.query!("SELECT max(level) FROM economy_v2_xp_thresholds WHERE required_xp<=$1", [xp]).rows do
      [[level]] -> level
    end
  end
  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
