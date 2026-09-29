defmodule ToriEconomy.Contract do
  @moduledoc "Strict v1 boundary validation and stable payload fingerprinting."

  @snowflake ~r/^[0-9]{1,32}$/
  @uuid ~r/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
  @max_amount 9_223_372_036_854_775_807
  @mutations ["daily.claim", "wallet.transfer"]
  @reads ["wallet.balance", "inventory.list", "shop.catalog", "wallet.leaderboard"]
  @operations @mutations ++ @reads

  def validate(raw) when is_map(raw) do
    with :ok <- keys(raw, ["request_id", "idempotency_key", "operation", "context", "args"]),
         request_id when is_binary(request_id) <- raw["request_id"],
         true <- Regex.match?(@uuid, request_id),
         operation when operation in @operations <- raw["operation"],
         context when is_map(context) <- raw["context"],
         args when is_map(args) <- raw["args"],
         :ok <- keys(context, ["actor_user_id", "target_user_id", "guild_id", "channel_id"]),
         :ok <- snowflake(context["actor_user_id"]),
         :ok <- snowflake(context["guild_id"]),
         :ok <- snowflake(context["channel_id"]),
         :ok <- optional_snowflake(context["target_user_id"]),
         :ok <- validate_args(operation, args),
         :ok <- validate_key(operation, raw["idempotency_key"]) do
      {:ok,
       %{
         request_id: request_id,
         operation: operation,
         context: context,
         args: args,
         idempotency_key: raw["idempotency_key"],
         fingerprint: fingerprint(operation, context, args)
       }}
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  def validate(_), do: {:error, "INVALID_INPUT"}

  defp validate_args("wallet.balance", args), do: keys(args, [])
  defp validate_args("daily.claim", args), do: keys(args, [])
  defp validate_args("inventory.list", args), do: keys(args, [])

  defp validate_args("shop.catalog", args) do
    with :ok <- keys(args, ["category"]),
         category when is_binary(category) <- Map.get(args, "category", "all"),
         true <- category in ["all", "utility"] or Regex.match?(~r/^[a-z0-9_-]{1,64}$/, category) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp validate_args("wallet.leaderboard", args) do
    with :ok <- keys(args, ["limit"]),
         limit when is_integer(limit) and limit >= 1 and limit <= 100 <- Map.get(args, "limit", 10) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp validate_args("wallet.transfer", args) do
    with :ok <- keys(args, ["recipient_user_id", "amount"]),
         :ok <- snowflake(args["recipient_user_id"]),
         amount when is_binary(amount) <- args["amount"],
         true <- Regex.match?(~r/^[1-9][0-9]*$/, amount),
         {parsed, ""} <- Integer.parse(amount),
         true <- parsed <= @max_amount do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp validate_key(operation, nil) when operation in @reads, do: :ok

  defp validate_key(operation, "discord-interaction:" <> id) when operation in @mutations,
    do: snowflake(id)

  defp validate_key(_, _), do: {:error, "INVALID_INPUT"}

  defp snowflake(value) when is_binary(value) do
    if Regex.match?(@snowflake, value) and String.trim(value, "0") != "",
      do: :ok,
      else: {:error, "INVALID_INPUT"}
  end

  defp snowflake(_), do: {:error, "INVALID_INPUT"}
  defp optional_snowflake(nil), do: :ok
  defp optional_snowflake(value), do: snowflake(value)

  defp keys(map, allowed) do
    if Enum.all?(Map.keys(map), &(&1 in allowed)), do: :ok, else: {:error, "INVALID_INPUT"}
  end

  def fingerprint(operation, context, args) do
    canonical([operation, context, args])
    |> Jason.encode!()
    |> then(&:crypto.hash(:sha256, &1))
    |> Base.encode16(case: :lower)
  end

  defp canonical(value) when is_map(value) do
    value |> Enum.map(fn {key, item} -> [key, canonical(item)] end) |> Enum.sort()
  end

  defp canonical(value) when is_list(value), do: Enum.map(value, &canonical/1)
  defp canonical(value), do: value
end
