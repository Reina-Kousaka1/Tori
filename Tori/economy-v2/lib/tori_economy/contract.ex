defmodule ToriEconomy.Contract do
  @moduledoc "Strict v1 boundary validation and stable payload fingerprinting."

  @snowflake ~r/^[0-9]{1,32}$/
  @uuid ~r/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
  @max_amount 9_223_372_036_854_775_807
  @mutations ["daily.claim", "wallet.transfer", "shop.purchase", "inventory.equip",
              "inventory.unequip", "progression.grant", "marketplace.list",
              "marketplace.buy", "marketplace.cancel", "activity.perform", "inventory.consume",
              "inventory.cosmetic.select", "inventory.cosmetic.clear", "career.select",
              "career.practice"]
  @reads ["wallet.balance", "inventory.list", "shop.catalog", "wallet.leaderboard", "profile.snapshot",
          "market.product", "market.history", "shop.rotation", "progression.snapshot",
          "marketplace.browse", "inventory.effects", "shop.item", "inventory.cosmetics",
          "career.snapshot", "inventory.item", "wardrobe.list", "marketplace.inspect"]
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
      {:error, code} when code in ["INVALID_AMOUNT", "INVALID_TARGET"] -> {:error, code}
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  def validate(_), do: {:error, "INVALID_INPUT"}

  defp validate_args("wallet.balance", args), do: keys(args, [])
  defp validate_args("daily.claim", args), do: keys(args, [])
  defp validate_args(operation, args) when operation in ["inventory.list", "wardrobe.list"] do
    with :ok <- keys(args, ["category", "page"]),
         category when is_binary(category) <- Map.get(args, "category", "all"),
         true <- category == "all" or Regex.match?(~r/^[a-z0-9_-]{1,64}$/, category),
         page when is_integer(page) and page in 0..1000 <- Map.get(args, "page", 0) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("inventory.item", args) do
    with :ok <- keys(args, ["item_id"]), :ok <- item_id(args["item_id"]), do: :ok
  end
  defp validate_args("profile.snapshot", args), do: keys(args, [])
  defp validate_args("shop.rotation", args) do
    with :ok <- keys(args, ["category", "page"]),
         category when is_binary(category) <- Map.get(args, "category", "all"),
         true <- category == "all" or Regex.match?(~r/^[a-z0-9_-]{1,64}$/, category),
         page when is_integer(page) and page >= 0 and page <= 1000 <- Map.get(args, "page", 0) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("shop.item", args) do
    with :ok <- keys(args, ["item_id", "period_key"]),
         :ok <- item_id(args["item_id"]),
         key when is_binary(key) <- args["period_key"],
         true <- Regex.match?(~r/^[0-9]{1,15}$/, key) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("inventory.cosmetics", args), do: keys(args, [])
  defp validate_args("career.snapshot", args), do: keys(args, [])
  defp validate_args("career.select", args) do
    with :ok <- keys(args, ["career_code"]),
         career when career in ["ballet", "volleyball", "cheer"] <- args["career_code"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("career.practice", args) do
    with :ok <- keys(args, ["career_code", "action_code"]),
         career when career in ["ballet", "volleyball", "cheer"] <- args["career_code"],
         action when action in ["practice"] <- args["action_code"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("inventory.cosmetic.select", args) do
    with :ok <- keys(args, ["item_id", "slot"]),
         :ok <- item_id(args["item_id"]),
         slot when slot in ["nails", "makeup", "hair_accessory"] <- args["slot"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("inventory.cosmetic.clear", args) do
    with :ok <- keys(args, ["slot"]),
         slot when slot in ["nails", "makeup", "hair_accessory"] <- args["slot"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("progression.snapshot", args), do: keys(args, [])
  defp validate_args("marketplace.browse", args) do
    with :ok <- keys(args, ["category", "page"]),
         category when is_binary(category) <- Map.get(args, "category", "all"),
         true <- category == "all" or Regex.match?(~r/^[a-z0-9_-]{1,64}$/, category),
         page when is_integer(page) and page in 0..1000 <- Map.get(args, "page", 0) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("marketplace.inspect", args) do
    with :ok <- keys(args, ["listing_id"]), :ok <- item_id(args["listing_id"]), do: :ok
  end
  defp validate_args("inventory.effects", args), do: keys(args, [])
  defp validate_args("inventory.consume", args) do
    with :ok <- keys(args, ["item_id"]), :ok <- item_id(args["item_id"]), do: :ok
  end
  defp validate_args("activity.perform", args) do
    with :ok <- keys(args, ["activity"]),
         activity when activity in ["fish", "mine", "chop"] <- args["activity"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp validate_args("shop.purchase", args) do
    with :ok <- keys(args, ["item_id", "quantity", "period_key"]),
         :ok <- item_id(args["item_id"]),
         quantity when is_integer(quantity) and quantity in 1..100 <- args["quantity"],
         key when is_binary(key) <- args["period_key"],
         true <- Regex.match?(~r/^[0-9]{1,15}$/, key) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp validate_args("inventory.equip", args) do
    with :ok <- keys(args, ["item_id", "slot"]), :ok <- item_id(args["item_id"]),
         slot when slot in ~w(top bottom dress outerwear shoes bag accessory jewelry hair_accessory) <- args["slot"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("inventory.unequip", args) do
    with :ok <- keys(args, ["slot"]),
         slot when slot in ~w(top bottom dress outerwear shoes bag accessory jewelry hair_accessory) <- args["slot"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args("progression.grant", args) do
    with :ok <- keys(args, ["source_code"]), :ok <- item_id(args["source_code"]), do: :ok
  end
  defp validate_args("marketplace.list", args) do
    with :ok <- keys(args, ["item_id", "quantity", "ask_price", "expires_hours"]),
         :ok <- item_id(args["item_id"]),
         quantity when is_integer(quantity) and quantity in 1..100 <- args["quantity"],
         price when is_binary(price) <- args["ask_price"],
         true <- Regex.match?(~r/^[1-9][0-9]{0,17}$/, price),
         hours when is_integer(hours) and hours in 1..168 <- args["expires_hours"] do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end
  defp validate_args(operation, args) when operation in ["marketplace.buy", "marketplace.cancel"] do
    with :ok <- keys(args, ["listing_id"]), :ok <- item_id(args["listing_id"]), do: :ok
  end

  defp item_id(value) when is_binary(value) do
    if Regex.match?(~r/^[a-z0-9_-]{1,64}$/, value), do: :ok, else: {:error, "INVALID_INPUT"}
  end
  defp item_id(_), do: {:error, "INVALID_INPUT"}

  defp validate_args("market.product", args), do: market_id(args, ["product_id"])

  defp validate_args("market.history", args) do
    with :ok <- market_id(args, ["product_id", "limit"]),
         limit when is_integer(limit) and limit >= 1 and limit <= 100 <- Map.get(args, "limit", 10) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp market_id(args, allowed) do
    with :ok <- keys(args, allowed),
         id when is_binary(id) <- args["product_id"],
         true <- Regex.match?(~r/^[a-z0-9_-]{1,64}$/, id) do
      :ok
    else
      _ -> {:error, "INVALID_INPUT"}
    end
  end

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
         :ok <- transfer_target(args["recipient_user_id"]),
         :ok <- transfer_amount(args["amount"]) do
      :ok
    else
      {:error, code} when code in ["INVALID_AMOUNT", "INVALID_TARGET"] -> {:error, code}
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp transfer_target(value) do
    case snowflake(value) do
      :ok -> :ok
      _ -> {:error, "INVALID_TARGET"}
    end
  end

  defp transfer_amount(amount) when is_binary(amount) do
    with true <- Regex.match?(~r/^[1-9][0-9]*$/, amount),
         {parsed, ""} <- Integer.parse(amount),
         true <- parsed <= @max_amount do
      :ok
    else
      _ -> {:error, "INVALID_AMOUNT"}
    end
  end

  defp transfer_amount(_), do: {:error, "INVALID_AMOUNT"}

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
