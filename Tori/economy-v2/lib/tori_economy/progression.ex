defmodule ToriEconomy.Progression do
  @moduledoc "Database-configured XP thresholds, idempotent grants and career progression."
  alias ToriEconomy.{Idempotency, Sql}

  @max_balance 9_223_372_036_854_775_807
  @equipment_bonus_xp 2
  @equipment_bonus_credits 5

  def execute(%{operation: operation} = request)
      when operation in ["progression.snapshot", "career.snapshot"] do
    user = request.context["target_user_id"] || request.context["actor_user_id"]

    xp =
      case Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1", [user]).rows do
        [[value]] -> value
        [] -> 0
      end

    level = level_data(xp)

    careers =
      Sql.query!(
        """
          SELECT c.career_code,c.display_name,coalesce(p.xp,0) FROM economy_v2_careers c
          LEFT JOIN economy_v2_career_progress p ON p.career_code=c.career_code AND p.user_id=$1
          WHERE c.active ORDER BY c.career_code
        """,
        [user]
      ).rows
      |> Enum.map(fn [code, name, career_xp] ->
        Map.merge(string_level_data(career_xp), %{
          "code" => code,
          "name" => name,
          "xp" => Integer.to_string(career_xp)
        })
      end)

    selected =
      case Sql.query!(
             """
               SELECT s.career_code,c.display_name,s.selected_at,
                      coalesce(p.xp,0)
               FROM economy_v2_career_selections s
               JOIN economy_v2_careers c ON c.career_code=s.career_code
               LEFT JOIN economy_v2_career_progress p ON p.user_id=s.user_id AND p.career_code=s.career_code
               WHERE s.user_id=$1
             """,
             [user]
           ).rows do
        [[code, name, selected_at, career_xp]] ->
          Map.merge(string_level_data(career_xp), %{
            "code" => code,
            "name" => name,
            "selected_at" => DateTime.to_iso8601(selected_at),
            "xp" => Integer.to_string(career_xp)
          })

        [] ->
          nil
      end

    {careers, selected} =
      if operation == "career.snapshot" do
        careers = attach_career_actions(careers, user, selected)

        selected =
          if selected do
            case Enum.find(careers, &(&1["code"] == selected["code"])) do
              nil -> selected
              career -> Map.put(career, "selected_at", selected["selected_at"])
            end
          end

        {careers, selected}
      else
        {careers, selected}
      end

    unlocks =
      Sql.query!(
        """
          SELECT c.item_id,c.name,c.category,c.rarity,c.career_requirement,c.career_level_requirement
          FROM economy_v2_catalog_items c
          WHERE c.active AND c.level_requirement<=$1 AND c.level_requirement>1
            AND (c.career_requirement IS NULL OR coalesce((
              SELECT max(t.level) FROM economy_v2_career_progress p
              LEFT JOIN economy_v2_xp_thresholds t ON t.required_xp<=p.xp
              WHERE p.user_id=$2 AND p.career_code=c.career_requirement
            ),1)>=c.career_level_requirement)
          ORDER BY level_requirement,item_id LIMIT 12
        """,
        [level.level, user]
      ).rows
      |> Enum.map(fn [id, name, category, rarity, career, career_level] ->
        %{"item_id" => id, "name" => name, "category" => category, "rarity" => rarity}
        |> Map.put("career_requirement", career)
        |> Map.put("career_level_requirement", career_level)
      end)

    {:ok,
     %{
       "request_id" => request.request_id,
       "status" => "ok",
       "result" => %{
         "type" => "progression",
         "user_id" => user,
         "xp" => Integer.to_string(xp),
         "level" => level.level,
         "level_start_xp" => Integer.to_string(level.level_start_xp),
         "next_level_xp" => if(level.next_level_xp, do: Integer.to_string(level.next_level_xp)),
         "active_career" => selected,
         "careers" => careers,
         "unlocks" => unlocks
       }
     }}
  end

  def execute(%{operation: "progression.grant"} = request) do
    Idempotency.run(request, fn -> grant(request) end,
      skip_persist_errors: ["REQUIREMENT_NOT_MET", "COOLDOWN_ACTIVE"]
    )
  end

  def execute(%{operation: "career.select"} = request) do
    Idempotency.run(request, fn -> select_career(request) end,
      skip_persist_errors: ["INVALID_TARGET", "COOLDOWN_ACTIVE"]
    )
  end

  def execute(%{operation: "career.practice"} = request) do
    Idempotency.run(request, fn -> practice_career(request) end,
      skip_persist_errors: ["REQUIREMENT_NOT_MET", "COOLDOWN_ACTIVE"]
    )
  end

  # Called inside an already locked, idempotent activity transaction. No
  # additional request record is created. Unconfigured sources grant no XP.
  def activity_effect(request, activity) when activity in ["fish", "mine", "chop"] do
    source = "activity_" <> activity
    user = request.context["actor_user_id"]

    case Sql.query!(
           "SELECT reward_xp,cooldown_ms,career_code FROM economy_v2_xp_sources WHERE source_code=$1 AND active FOR SHARE",
           [source]
         ).rows do
      [[reward, cooldown, career]] ->
        case grant_configured(request, user, source, reward, cooldown, career) do
          %{"status" => "ok", "result" => result} -> result
          _ -> nil
        end

      [] ->
        nil
    end
  end

  defp grant(request) do
    user = request.context["actor_user_id"]
    source = request.args["source_code"]
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])

    case Sql.query!(
           "SELECT reward_xp,cooldown_ms,career_code FROM economy_v2_xp_sources WHERE source_code=$1 AND active FOR SHARE",
           [source]
         ).rows do
      [[reward, cooldown, career]] ->
        grant_configured(request, user, source, reward, cooldown, career)

      [] ->
        error("REQUIREMENT_NOT_MET")
    end
  end

  defp grant_configured(request, user, source, reward, cooldown, career, bonus_xp \\ 0) do
    reward = reward + bonus_xp

    [[elapsed]] =
      Sql.query!(
        """
          SELECT coalesce(extract(epoch from (now()-max(occurred_at)))*1000, 9223372036854775807)::bigint
          FROM economy_v2_xp_events WHERE user_id=$1 AND source_code=$2
        """,
        [user, source]
      ).rows

    if elapsed < cooldown do
      error("COOLDOWN_ACTIVE")
    else
      Sql.query!(
        "INSERT INTO economy_v2_account_progress(user_id) VALUES ($1) ON CONFLICT DO NOTHING",
        [user]
      )

      [[old]] =
        Sql.query!("SELECT xp FROM economy_v2_account_progress WHERE user_id=$1 FOR UPDATE", [
          user
        ]).rows

      if old > 9_223_372_036_854_775_807 - reward do
        error("REQUIREMENT_NOT_MET")
      else
        previous_level = level_data(old).level
        next = old + reward
        new_level = level_data(next).level

        Sql.query!(
          "UPDATE economy_v2_account_progress SET xp=$2,updated_at=now() WHERE user_id=$1",
          [user, next]
        )

        career_after =
          if career do
            Sql.query!(
              "INSERT INTO economy_v2_career_progress(user_id,career_code) VALUES ($1,$2) ON CONFLICT DO NOTHING",
              [user, career]
            )

            [[career_old]] =
              Sql.query!(
                "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code=$2 FOR UPDATE",
                [user, career]
              ).rows

            if career_old > 9_223_372_036_854_775_807 - reward do
              ToriEconomy.Repo.rollback({:unpersisted_result, error("REQUIREMENT_NOT_MET")})
            else
              Sql.query!(
                "UPDATE economy_v2_career_progress SET xp=$3 WHERE user_id=$1 AND career_code=$2",
                [user, career, career_old + reward]
              )

              career_old + reward
            end
          end

        Sql.query!(
          """
            INSERT INTO economy_v2_xp_events
              (request_key,user_id,source_code,xp_delta,xp_after,career_code,career_xp_after)
            VALUES ($1,$2,$3,$4,$5,$6,$7)
          """,
          [request.idempotency_key, user, source, reward, next, career, career_after]
        )

        %{
          "status" => "ok",
          "result" => %{
            "type" => "xp_grant",
            "source_code" => source,
            "xp_awarded" => Integer.to_string(reward),
            "xp" => Integer.to_string(next),
            "level" => new_level,
            "level_before" => previous_level,
            "level_up" => new_level > previous_level,
            "career_code" => career,
            "presentation_key" =>
              if(new_level > previous_level,
                do: "progression.level_up",
                else: "progression.grant.success"
              )
          }
        }
      end
    end
  end

  defp select_career(request) do
    user = request.context["actor_user_id"]
    code = request.args["career_code"]
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])
    Sql.query!("SELECT user_id FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user])

    case Sql.query!(
           "SELECT career_code,selected_at FROM economy_v2_career_selections WHERE user_id=$1 FOR UPDATE",
           [user]
         ).rows do
      [] ->
        Sql.query!(
          "INSERT INTO economy_v2_career_selections(user_id,career_code) VALUES ($1,$2)",
          [user, code]
        )

        career_result(code, "career_selected")

      [[^code, _selected_at]] ->
        career_result(code, "career_selected")

      [[_old_code, selected_at]] ->
        [[hours]] =
          Sql.query!(
            "SELECT switch_cooldown_hours FROM economy_v2_career_policy WHERE policy_id=1"
          ).rows

        # Return a stable duration without converting PostgreSQL intervals through a locale.
        [[remaining]] =
          Sql.query!(
            """
              SELECT greatest(0, floor($1::double precision * 3600000 -
                extract(epoch from (now()-$2))*1000)::bigint)
            """,
            [hours, selected_at]
          ).rows

        if remaining > 0 do
          %{
            "status" => "error",
            "error" => %{
              "code" => "COOLDOWN_ACTIVE",
              "retryable" => false,
              "details" => %{"retry_after_ms" => remaining}
            }
          }
        else
          Sql.query!(
            "UPDATE economy_v2_career_selections SET career_code=$2,selected_at=now() WHERE user_id=$1",
            [user, code]
          )

          career_result(code, "career_selected")
        end
    end
  end

  defp practice_career(request) do
    user = request.context["actor_user_id"]
    career = request.args["career_code"]
    action = normalize_action(career, request.args["action_code"])
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])

    [[balance]] =
      Sql.query!("SELECT balance FROM economy_accounts WHERE user_id=$1 FOR UPDATE", [user]).rows

    case Sql.query!(
           """
             SELECT s.career_code,c.display_name FROM economy_v2_career_selections s
             JOIN economy_v2_careers c ON c.career_code=s.career_code WHERE s.user_id=$1
           """,
           [user]
         ).rows do
      [[^career, career_name]] ->
        case Sql.query!(
               """
                 SELECT a.source_code,x.reward_xp,x.cooldown_ms,a.reward_credits,
                        a.required_career_level,a.required_item_id
                 FROM economy_v2_career_actions a
                 JOIN economy_v2_xp_sources x ON x.source_code=a.source_code
                 WHERE a.career_code=$1 AND a.action_code=$2 AND x.active
               """,
               [career, action]
             ).rows do
          [[source, reward_xp, cooldown, reward_credits, required_level, required_item_id]] ->
            {_career_xp, career_level} = career_progress_data(user, career)
            {bonus_xp, bonus_credits} = career_equipment_bonus(user, career)
            total_credits = reward_credits + bonus_credits

            cond do
              career_level < required_level ->
                error("REQUIREMENT_NOT_MET")

              not required_item_equipped?(user, required_item_id) ->
                error("REQUIREMENT_NOT_MET")

              balance > @max_balance - total_credits ->
                error("REQUIREMENT_NOT_MET")

              true ->
                case grant_configured(
                       request,
                       user,
                       source,
                       reward_xp,
                       cooldown,
                       career,
                       bonus_xp
                     ) do
                  %{"status" => "ok", "result" => result} ->
                    next_balance = balance + total_credits

                    Sql.query!(
                      "UPDATE economy_accounts SET balance=$2,updated_at=now() WHERE user_id=$1",
                      [user, next_balance]
                    )

                    Sql.query!(
                      """
                        INSERT INTO economy_v2_ledger_entries
                          (user_id,request_key,leg,delta,balance_after,reason_code,guild_id)
                        VALUES ($1,$2,'career_reward',$3,$4,'CAREER_ACTION',$5)
                      """,
                      [
                        user,
                        request.idempotency_key,
                        total_credits,
                        next_balance,
                        request.context["guild_id"]
                      ]
                    )

                    [[career_xp]] =
                      Sql.query!(
                        "SELECT xp FROM economy_v2_career_progress WHERE user_id=$1 AND career_code=$2",
                        [user, career]
                      ).rows

                    career_level_after = level_data(career_xp).level

                    %{
                      "status" => "ok",
                      "result" =>
                        Map.merge(result, %{
                          "type" => "career_practice",
                          "career_code" => career,
                          "career_name" => career_name,
                          "action_code" => action,
                          "career_xp" => Integer.to_string(career_xp),
                          "career_level" => career_level_after,
                          "credits_awarded" => Integer.to_string(total_credits),
                          "balance" => Integer.to_string(next_balance),
                          "equipment_bonus_xp" => bonus_xp,
                          "equipment_bonus_credits" => bonus_credits,
                          "required_item_id" => required_item_id,
                          "presentation_key" => "career.practice.success"
                        })
                    }

                  other ->
                    other
                end
            end

          [] ->
            error("REQUIREMENT_NOT_MET")
        end

      _ ->
        error("REQUIREMENT_NOT_MET")
    end
  end

  defp normalize_action("ballet", "practice"), do: "class"
  defp normalize_action("volleyball", "practice"), do: "court_practice"
  defp normalize_action("cheer", "practice"), do: "squad_practice"
  defp normalize_action(_career, action), do: action

  defp career_progress_data(user, career) do
    case Sql.query!(
           """
             SELECT coalesce(p.xp,0),coalesce(max(t.level),1)
             FROM economy_v2_careers c
             LEFT JOIN economy_v2_career_progress p
               ON p.user_id=$1 AND p.career_code=c.career_code
             LEFT JOIN economy_v2_xp_thresholds t ON t.required_xp<=coalesce(p.xp,0)
             WHERE c.career_code=$2
             GROUP BY p.xp
           """,
           [user, career]
         ).rows do
      [[xp, level]] -> {xp, level}
      [] -> {0, 1}
    end
  end

  defp required_item_equipped?(_user, nil), do: true

  defp required_item_equipped?(user, item_id) do
    case Sql.query!(
           """
             SELECT l.item_id
             FROM economy_v2_loadout l
             JOIN economy_inventory i ON i.user_id=l.user_id AND i.item_id=l.item_id
             JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
             WHERE l.user_id=$1 AND l.item_id=$2 AND l.slot=ANY(c.equip_slots)
               AND i.quantity>0 AND c.active
             FOR UPDATE OF l
           """,
           [user, item_id]
         ).rows do
      [[_item_id]] -> true
      [] -> false
    end
  end

  # A single career-matching V11 outfit grants a flat, non-stacking bonus.
  defp career_equipment_bonus(user, career) do
    equipped =
      Sql.query!(
        """
          SELECT l.item_id
          FROM economy_v2_loadout l
          JOIN economy_inventory i ON i.user_id=l.user_id AND i.item_id=l.item_id
          JOIN economy_v2_catalog_items c ON c.item_id=l.item_id
          WHERE l.user_id=$1 AND c.category=$2 AND 'content_drop_v1'=ANY(c.tags)
            AND l.slot=ANY(c.equip_slots) AND i.quantity>0 AND c.active
          ORDER BY l.slot
          LIMIT 1
          FOR UPDATE OF l
        """,
        [user, career]
      ).rows != []

    if equipped,
      do: {@equipment_bonus_xp, @equipment_bonus_credits},
      else: {0, 0}
  end

  defp attach_career_actions(careers, user, selected) do
    selected_code = if selected, do: selected["code"]

    actions_by_career =
      Sql.query!(
        """
          SELECT a.career_code,a.action_code,a.display_name,x.reward_xp,x.cooldown_ms,
                 a.reward_credits,a.required_career_level,a.required_item_id,
                 item.name,coalesce(item.equip_slots,ARRAY[]::text[]),
                 coalesce((
                   SELECT greatest(
                     0,
                     a.cooldown_ms -
                       floor(extract(epoch FROM (now()-max(xp_event.occurred_at)))*1000)::bigint
                   )
                   FROM economy_v2_xp_events xp_event
                   WHERE xp_event.user_id=$1 AND xp_event.source_code=a.source_code
                 ),0),
                 CASE WHEN a.required_item_id IS NULL THEN true ELSE EXISTS(
                   SELECT 1
                   FROM economy_v2_loadout l
                   JOIN economy_inventory i ON i.user_id=l.user_id AND i.item_id=l.item_id
                   JOIN economy_v2_catalog_items required ON required.item_id=l.item_id
                   WHERE l.user_id=$1 AND l.item_id=a.required_item_id
                     AND l.slot=ANY(required.equip_slots) AND i.quantity>0 AND required.active
                 ) END
          FROM economy_v2_career_actions a
          JOIN economy_v2_xp_sources x ON x.source_code=a.source_code AND x.active
          LEFT JOIN economy_v2_catalog_items item ON item.item_id=a.required_item_id
          ORDER BY a.career_code,a.required_career_level,a.action_code
        """,
        [user]
      ).rows
      |> Enum.group_by(fn [career_code | _] -> career_code end)

    Enum.map(careers, fn career ->
      actions =
        Map.get(actions_by_career, career["code"], [])
        |> Enum.map(fn [
                         _career_code,
                         action_code,
                         display_name,
                         reward_xp,
                         cooldown_ms,
                         reward_credits,
                         required_level,
                         required_item_id,
                         item_name,
                         item_slots,
                         cooldown_remaining,
                         item_equipped
                       ] ->
          availability =
            cond do
              career["code"] != selected_code -> "select_career"
              career["level"] < required_level -> "career_level"
              not item_equipped -> "equipment"
              cooldown_remaining > 0 -> "cooldown"
              true -> "ready"
            end

          %{
            "action_code" => action_code,
            "display_name" => display_name,
            "reward_xp" => Integer.to_string(reward_xp),
            "reward_credits" => Integer.to_string(reward_credits),
            "cooldown_ms" => cooldown_ms,
            "cooldown_remaining_ms" => cooldown_remaining,
            "required_career_level" => required_level,
            "required_item_id" => required_item_id,
            "required_item_name" => item_name,
            "required_item_slots" => item_slots || [],
            "required_item_equipped" => item_equipped,
            "availability" => availability,
            "available" => availability == "ready"
          }
        end)

      Map.put(career, "actions", actions)
    end)
  end

  defp string_level_data(xp) do
    level = level_data(xp)

    %{
      "level" => level.level,
      "level_start_xp" => level.level_start_xp,
      "next_level_xp" => level.next_level_xp
    }
  end

  defp career_result(code, type) do
    [[name]] =
      Sql.query!("SELECT display_name FROM economy_v2_careers WHERE career_code=$1 AND active", [
        code
      ]).rows

    %{
      "status" => "ok",
      "result" => %{
        "type" => type,
        "career_code" => code,
        "career_name" => name,
        "presentation_key" => "career.select.success"
      }
    }
  end

  defp level_data(xp) do
    case Sql.query!("SELECT max(level) FROM economy_v2_xp_thresholds WHERE required_xp<=$1", [xp]).rows do
      [[level]] ->
        [[start_xp]] =
          Sql.query!("SELECT required_xp FROM economy_v2_xp_thresholds WHERE level=$1", [level]).rows

        next =
          case Sql.query!("SELECT required_xp FROM economy_v2_xp_thresholds WHERE level=$1", [
                 level + 1
               ]).rows do
            [[value]] -> value
            [] -> nil
          end

        %{level: level || 1, level_start_xp: start_xp || 0, next_level_xp: next}
    end
  end

  defp error(code), do: %{"status" => "error", "error" => %{"code" => code, "retryable" => false}}
end
