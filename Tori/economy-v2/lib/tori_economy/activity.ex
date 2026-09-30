defmodule ToriEconomy.Activity do
  @moduledoc "Test-gated fish/mine/chop parity using the existing wallet, tools and cooldowns."
  alias ToriEconomy.{Catalog, Idempotency, Inventory, Progression, Sql}
  alias ToriEconomy.Activity.Policy

  @cooldown_ms 60_000
  @slots %{"fish" => "rod", "mine" => "pickaxe", "chop" => "axe"}

  def execute(request, random \\ &:rand.uniform/1) when is_function(random, 1) do
    Idempotency.run(request, fn -> perform(request, random) end,
      skip_persist_errors: ["COOLDOWN_ACTIVE", "REQUIREMENT_NOT_MET", "MAX_STACK_REACHED"]
    )
  end

  defp perform(request, random) do
    user = request.context["actor_user_id"]
    activity = request.args["activity"]
    slot = Map.fetch!(@slots, activity)
    Sql.query!("INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT DO NOTHING", [user])

    [[balance, last]] =
      Sql.query!(
        "SELECT balance,last_#{activity}_at FROM economy_accounts WHERE user_id=$1 FOR UPDATE",
        [user]
      ).rows

    [[now]] = Sql.query!("SELECT floor(extract(epoch from clock_timestamp())*1000)::bigint").rows
    wait = if last == 0, do: 0, else: max(0, @cooldown_ms - (now - last))

    if wait > 0 do
      error("COOLDOWN_ACTIVE", %{"retry_after_ms" => wait})
    else
      case Sql.query!(
             """
               SELECT e.item_id,c.tool_slot,c.durability FROM economy_equipment e
               JOIN economy_v2_catalog_items c ON c.item_id=e.item_id
               WHERE e.user_id=$1 AND e.slot=$2
             """,
             [user, slot]
           ).rows do
        [[tool_id, ^slot, durability]] ->
          gather(request, random, user, activity, slot, tool_id, durability, balance, now)

        _ ->
          error("REQUIREMENT_NOT_MET")
      end
    end
  end

  defp gather(request, random, user, activity, slot, tool_id, durability, balance, now) do
    owned = Inventory.quantity(user, tool_id)

    wear =
      case Sql.query!("SELECT used FROM economy_tool_wear WHERE user_id=$1 AND item_id=$2", [
             user,
             tool_id
           ]).rows do
        [[value]] -> value
        [] -> 0
      end

    cond do
      owned < 1 or wear >= durability ->
        error("REQUIREMENT_NOT_MET")

      true ->
        tier = Policy.tier(durability)
        base_reward = Policy.credits(tier, random.(16 * tier) - 1)

        [[bonus_percent]] =
          Sql.query!(
            """
              SELECT coalesce(max(r.credit_bonus_percent),0)
              FROM economy_v2_active_effects e
              JOIN economy_v2_activity_effect_rules r ON r.effect_code=e.effect_code
              WHERE e.user_id=$1 AND e.expires_at>now() AND r.activity=$2 AND r.active
            """,
            [user, activity]
          ).rows

        credit_reward = base_reward + div(base_reward * bonus_percent, 100)
        drop_id = Policy.drop(activity, tier, random.(100) - 1)

        if balance > 9_223_372_036_854_775_807 - credit_reward do
          error("REQUIREMENT_NOT_MET")
        else
          with {:ok, drop_item} <- Catalog.fetch_for_update(drop_id),
               {:ok, _} <-
                 Inventory.grant(user, drop_item, 1, request, "activity_drop", "ACTIVITY_REWARD") do
            finish(
              request,
              user,
              activity,
              slot,
              tool_id,
              durability,
              wear,
              balance,
              now,
              credit_reward,
              drop_id
            )
          else
            {:error, code} -> error(code)
          end
        end
    end
  end

  defp finish(
         request,
         user,
         activity,
         slot,
         tool_id,
         durability,
         wear,
         balance,
         now,
         credits,
         drop_id
       ) do
    next = balance + credits

    Sql.query!(
      "UPDATE economy_accounts SET balance=$2,last_#{activity}_at=$3,updated_at=now() WHERE user_id=$1",
      [user, next, now]
    )

    Sql.query!(
      """
        INSERT INTO economy_tool_wear(user_id,item_id,used) VALUES ($1,$2,$3)
        ON CONFLICT (user_id,item_id) DO UPDATE SET used=EXCLUDED.used
      """,
      [user, tool_id, wear + 1]
    )

    broke = wear + 1 >= durability

    if broke do
      Sql.query!("DELETE FROM economy_equipment WHERE user_id=$1 AND slot=$2 AND item_id=$3", [
        user,
        slot,
        tool_id
      ])
    end

    Sql.query!(
      """
        INSERT INTO economy_v2_ledger_entries
          (user_id,request_key,leg,delta,balance_after,reason_code,guild_id)
        VALUES ($1,$2,'activity_credit',$3,$4,$5,$6)
      """,
      [
        user,
        request.idempotency_key,
        credits,
        next,
        "ACTIVITY_#{String.upcase(activity)}",
        request.context["guild_id"]
      ]
    )

    Sql.query!(
      """
        INSERT INTO economy_v2_activity_events(user_id,request_key,activity,reward_credits)
        VALUES ($1,$2,$3,$4)
      """,
      [user, request.idempotency_key, activity, credits]
    )

    xp = Progression.activity_effect(request, activity)

    %{
      "status" => "ok",
      "result" => %{
        "type" => "activity",
        "activity" => activity,
        "credits" => Integer.to_string(credits),
        "drop_item_id" => drop_id,
        "tool_remaining" => max(0, durability - wear - 1),
        "tool_broke" => broke,
        "balance" => Integer.to_string(next),
        "xp" => xp,
        "presentation_key" => "activity.#{activity}.success"
      }
    }
  end

  defp error(code, details \\ %{}),
    do: %{
      "status" => "error",
      "error" => %{"code" => code, "retryable" => false, "details" => details}
    }
end
