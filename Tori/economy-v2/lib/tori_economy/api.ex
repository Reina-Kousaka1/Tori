defmodule ToriEconomy.Api do
  @moduledoc "Private, versioned HTTP boundary. It is not a public or website API."
  import Plug.Conn
  require Logger

  alias ToriEconomy.{Accounts, Contract, Queries, Sql, WriteGate}

  def init(opts), do: opts

  def call(conn, _opts) do
    case {conn.method, conn.request_path} do
      {"GET", "/internal/economy/v1/health"} ->
        try do
          Sql.query!("SELECT 1")
          reply(conn, 200, %{"status" => "ok"})
        rescue
          _ -> reply(conn, 503, error(nil, "SERVICE_UNAVAILABLE", true))
        end

      {"POST", "/internal/economy/v1/execute"} ->
        execute(conn)

      _ ->
        reply(conn, 404, error(nil, "INVALID_INPUT", false))
    end
  end

  defp execute(conn) do
    with :ok <- authorized(conn),
         :ok <- json_content_type(conn),
         {:ok, body, conn} <- read_body(conn, length: 32_768),
         {:ok, raw} <- Jason.decode(body),
         {:ok, request} <- Contract.validate(raw),
         :ok <- writes_allowed(request),
         {:ok, response} <- execute_request(request) do
      code = if get_in(response, ["error", "code"]) == "IDEMPOTENCY_CONFLICT", do: 409, else: 200
      reply(conn, code, response)
    else
      {:error, "UNAUTHORIZED"} ->
        reply(conn, 401, error(nil, "FORBIDDEN", false))

      {:error, "READ_ONLY"} ->
        reply(conn, 403, error(nil, "READ_ONLY", false))

      {:more, _body, conn} ->
        reply(conn, 413, error(nil, "INVALID_INPUT", false))

      {:error, "SERVICE_UNAVAILABLE"} ->
        reply(conn, 503, error(nil, "SERVICE_UNAVAILABLE", true))

      {:error, code} when code in ["INVALID_AMOUNT", "INVALID_TARGET"] ->
        reply(conn, 400, error(nil, code, false))

      _ ->
        reply(conn, 400, error(nil, "INVALID_INPUT", false))
    end
  rescue
    failure ->
      # No exception message, SQL text, request body or credential in logs.
      Logger.error("Economy request failed (#{inspect(failure.__struct__)})")
      reply(conn, 500, error(nil, "INTERNAL_ERROR", false))
  end

  # A running API is not permission to create a second wallet writer.
  defp writes_allowed(%{operation: operation})
       when operation in ["wallet.balance", "inventory.list", "shop.catalog", "wallet.leaderboard"],
       do: :ok

  defp writes_allowed(_request) do
    if WriteGate.writes_enabled?(),
      do: :ok,
      else: {:error, "READ_ONLY"}
  end

  defp execute_request(request) do
    started = System.monotonic_time(:millisecond)

    result =
      if request.operation in ["inventory.list", "shop.catalog", "wallet.leaderboard"],
        do: Queries.execute(request),
        else: Accounts.execute(request)

    status =
      case result do
        {:ok, %{"status" => "ok"}} -> "OK"
        {:ok, %{"error" => %{"code" => code}}} -> code
        {:error, code} -> code
        _ -> "INTERNAL_ERROR"
      end

    interaction_id = request.idempotency_key || "none"

    Logger.info(
      "economy request_id=#{request.request_id} interaction_id=#{interaction_id} " <>
        "operation=#{request.operation} status=#{status} duration_ms=#{System.monotonic_time(:millisecond) - started}"
    )

    result
  end

  defp authorized(conn) do
    expected = System.fetch_env!("TORI_ECONOMY_API_SECRET")

    case get_req_header(conn, "authorization") do
      ["Bearer " <> supplied] ->
        if byte_size(supplied) == byte_size(expected) and
             Plug.Crypto.secure_compare(supplied, expected),
           do: :ok,
           else: {:error, "UNAUTHORIZED"}

      _ ->
        {:error, "UNAUTHORIZED"}
    end
  end

  defp json_content_type(conn) do
    case get_req_header(conn, "content-type") do
      ["application/json" <> _] -> :ok
      _ -> {:error, "INVALID_INPUT"}
    end
  end

  defp error(request_id, code, retryable) do
    %{
      "request_id" => request_id,
      "status" => "error",
      "error" => %{"code" => code, "retryable" => retryable}
    }
  end

  defp reply(conn, status, body) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(body))
  end
end
