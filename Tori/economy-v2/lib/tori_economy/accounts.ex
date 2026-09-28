defmodule ToriEconomy.Accounts do
  @moduledoc "Global wallet operations. No guild-keyed wallet is created."
  alias ToriEconomy.{Idempotency, Sql}

  @daily_reward 150
  @daily_ms 86_400_000
  @max_balance 9_223_372_036_854_775_807

  def execute(%{operation: "wallet.balance"} = request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]

    rows = Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1", [user_id]).rows

    balance =
      case rows do
        [[value]] -> value
        [] -> 0
      end

    {:ok,
     %{
       "request_id" => request.request_id,
       "status" => "ok",
       "result" => %{
         "type" => "wallet_balance",
         "user_id" => user_id,
         "balance" => Integer.to_string(balance)
       }
     }}
  end

  def execute(%{operation: "daily.claim"} = request) do
    Idempotency.run(request, fn -> claim_daily(request) end)
  end

  def execute(%{operation: "wallet.transfer"} = request) do
    Idempotency.run(request, fn -> transfer(request) end)
  end

  defp claim_daily(request) do
    user_id = request.context["target_user_id"] || request.context["actor_user_id"]
    ensure_account(user_id)

    [[balance, last_daily_at]] =
      Sql.query!(
        """
        SELECT balance, last_daily_at FROM economy_accounts WHERE user_id = $1 FOR UPDATE
        """,
        [user_id]
      ).rows

    now = System.system_time(:millisecond)
    wait = if last_daily_at > 0, do: max(0, @daily_ms - (now - last_daily_at)), else: 0

    cond do
      wait > 0 ->
        error("COOLDOWN_ACTIVE", %{"retry_after_ms" => wait})

      balance > @max_balance - @daily_reward ->
        error("INVALID_INPUT")

      true ->
        next = balance + @daily_reward

        Sql.query!(
          """
          UPDATE economy_accounts SET balance = $2, last_daily_at = $3, updated_at = now()
          WHERE user_id = $1
          """,
          [user_id, next, now]
        )

        ledger(user_id, request, "daily", @daily_reward, next, "DAILY", nil)

        success(%{
          "type" => "daily_claimed",
          "recipient_user_id" => user_id,
          "credits_awarded" => Integer.to_string(@daily_reward),
          "balance" => Integer.to_string(next)
        })
    end
  end

  defp transfer(request) do
    sender = request.context["actor_user_id"]
    recipient = request.args["recipient_user_id"]
    amount = String.to_integer(request.args["amount"])

    if sender == recipient do
      error("INVALID_INPUT")
    else
      [first, second] = Enum.sort([sender, recipient])
      ensure_account(first)
      ensure_account(second)

      [[first_balance]] =
        Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1 FOR UPDATE", [first]).rows

      [[second_balance]] =
        Sql.query!("SELECT balance FROM economy_accounts WHERE user_id = $1 FOR UPDATE", [second]).rows

      from = if sender == first, do: first_balance, else: second_balance
      to = if recipient == first, do: first_balance, else: second_balance

      cond do
        from < amount ->
          error("INSUFFICIENT_FUNDS")

        to > @max_balance - amount ->
          error("INVALID_INPUT")

        true ->
          Sql.query!(
            "UPDATE economy_accounts SET balance = $2, updated_at = now() WHERE user_id = $1",
            [sender, from - amount]
          )

          Sql.query!(
            "UPDATE economy_accounts SET balance = $2, updated_at = now() WHERE user_id = $1",
            [recipient, to + amount]
          )

          ledger(sender, request, "debit", -amount, from - amount, "TRANSFER_OUT", recipient)
          ledger(recipient, request, "credit", amount, to + amount, "TRANSFER_IN", sender)

          success(%{
            "type" => "wallet_transfer",
            "recipient_user_id" => recipient,
            "amount" => Integer.to_string(amount),
            "balance" => Integer.to_string(from - amount)
          })
      end
    end
  end

  defp ensure_account(user_id) do
    Sql.query!(
      "INSERT INTO economy_accounts(user_id) VALUES ($1) ON CONFLICT (user_id) DO NOTHING",
      [user_id]
    )
  end

  defp ledger(user_id, request, leg, delta, balance, reason, counterparty) do
    Sql.query!(
      """
      INSERT INTO economy_v2_ledger_entries
        (user_id, request_key, leg, delta, balance_after, reason_code, guild_id, counterparty_user_id)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
      """,
      [
        user_id,
        request.idempotency_key,
        leg,
        delta,
        balance,
        reason,
        request.context["guild_id"],
        counterparty
      ]
    )
  end

  defp success(result), do: %{"status" => "ok", "result" => result}

  defp error(code, details \\ %{}),
    do: %{
      "status" => "error",
      "error" => %{"code" => code, "retryable" => false, "details" => details}
    }
end
