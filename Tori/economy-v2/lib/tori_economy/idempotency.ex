defmodule ToriEconomy.Idempotency do
  @moduledoc "Stores a completed command result in the same transaction as every effect."
  alias ToriEconomy.{Repo, Sql}

  def run(request, fun) when is_function(fun, 0) do
    case Repo.transaction(fn -> run_in_transaction(request, fun) end) do
      {:ok, result} -> {:ok, Map.put(result, "request_id", request.request_id)}
      {:error, _} -> {:error, "TEMPORARILY_UNAVAILABLE"}
    end
  end

  defp run_in_transaction(request, fun) do
    context = request.context

    inserted =
      Sql.query!(
        """
        INSERT INTO economy_v2_requests
          (idempotency_key, payload_hash, operation, actor_user_id, guild_id, result_json)
        VALUES ($1, $2, $3, $4, $5, '{}'::jsonb)
        ON CONFLICT (idempotency_key) DO NOTHING
        RETURNING idempotency_key
        """,
        [
          request.idempotency_key,
          request.fingerprint,
          request.operation,
          context["actor_user_id"],
          context["guild_id"]
        ]
      ).rows

    if inserted == [] do
      [[old_hash, json]] =
        Sql.query!(
          """
          SELECT payload_hash, result_json FROM economy_v2_requests WHERE idempotency_key = $1
          """,
          [request.idempotency_key]
        ).rows

      if String.trim(old_hash) == request.fingerprint do
        json
      else
        %{
          "status" => "error",
          "error" => %{"code" => "IDEMPOTENCY_CONFLICT", "retryable" => false}
        }
      end
    else
      result = fun.()

      Sql.query!(
        """
        UPDATE economy_v2_requests SET result_json = $2 WHERE idempotency_key = $1
        """,
        [request.idempotency_key, result]
      )

      result
    end
  end
end
