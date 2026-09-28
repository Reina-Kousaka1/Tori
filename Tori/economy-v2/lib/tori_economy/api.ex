defmodule ToriEconomy.Api do
  @moduledoc "Private, versioned HTTP boundary. It is not a public or website API."
  import Plug.Conn
  require Logger

  alias ToriEconomy.{Accounts, Contract, Sql}

  def init(opts), do: opts

  def call(conn, _opts) do
    case {conn.method, conn.request_path} do
      {"GET", "/internal/economy/v1/health"} ->
        try do
          Sql.query!("SELECT 1")
          reply(conn, 200, %{"status" => "ok"})
        rescue
          _ -> reply(conn, 503, error(nil, "TEMPORARILY_UNAVAILABLE", true))
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
         {:ok, response} <- Accounts.execute(request) do
      code = if get_in(response, ["error", "code"]) == "IDEMPOTENCY_CONFLICT", do: 409, else: 200
      reply(conn, code, response)
    else
      {:error, "UNAUTHORIZED"} ->
        reply(conn, 401, error(nil, "FORBIDDEN", false))

      {:more, _body, conn} ->
        reply(conn, 413, error(nil, "INVALID_INPUT", false))

      {:error, "TEMPORARILY_UNAVAILABLE"} ->
        reply(conn, 503, error(nil, "TEMPORARILY_UNAVAILABLE", true))

      _ ->
        reply(conn, 400, error(nil, "INVALID_INPUT", false))
    end
  rescue
    failure ->
      # No exception message, SQL text, request body or credential in logs.
      Logger.error("Economy request failed (#{inspect(failure.__struct__)})")
      reply(conn, 503, error(nil, "TEMPORARILY_UNAVAILABLE", true))
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
